package asia.creat.model;

import asia.creat.agent.AgentBudget;
import asia.creat.agent.model.OpenAiReasoningChatModel;
import asia.creat.common.exception.BusinessException;
import asia.creat.config.*;
import asia.creat.retrieval.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.FinishReason;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import lombok.RequiredArgsConstructor;
import okhttp3.*;
import org.springframework.stereotype.Service;

/** 人工探测与实际运行状态分开保存，页面读取不发起任何外部请求。 */
@Service
@RequiredArgsConstructor
public class AiDependencyHealth {
    public record Observation(String status, String detail, String checkedAt, long durationMs) { }
    public record Status(String id, String name, boolean configured, boolean usageCost,
                         Observation probe, Observation runtime) { }

    private final UserModelService models;
    private final AgentProperties agent;
    private final EmbeddingProperties embedding;
    private final RerankProperties rerank;
    private final MilvusProperties milvus;
    private final ElasticsearchProperties elasticsearch;
    private final McpProperties mcp;
    private final SiliconFlowEmbeddingClient embeddings;
    private final SiliconFlowRerankClient reranker;
    private final MilvusVectorClient vectors;
    private final ObjectMapper json;
    private final Cache<String, Observation> probes = Caffeine.newBuilder().maximumSize(10000)
            .expireAfterWrite(Duration.ofMinutes(5)).build();
    private final Cache<String, Observation> runtime = Caffeine.newBuilder().maximumSize(10000)
            .expireAfterWrite(Duration.ofHours(1)).build();
    private final Semaphore slots = new Semaphore(2);
    private final OkHttpClient http = new OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();

    public List<Status> status(long userId) {
        List<Status> result = new ArrayList<>();
        var personal = models.view(userId);
        add(result, "model", personal.enabled() ? "我的问答模型" : "系统问答模型",
                personal.enabled() ? personal.hasKey() && personal.encryptionReady()
                        && !Boolean.FALSE.equals(agent.getEnabled()) && !Boolean.FALSE.equals(agent.getAllowDocumentEgress())
                        : configuredModel(), true,
                modelKey(userId, personal.version(), personal.enabled()));
        add(result, "embedding", "Embedding", embeddings.enabled() && present(embedding.getBaseUrl()) && present(embedding.getModelName()), true, "embedding");
        add(result, "milvus", "Milvus", milvus.isEnabled() && present(milvus.getUrl()), false, "milvus");
        add(result, "reranker", "Reranker", reranker.enabled() && present(rerank.getBaseUrl()) && present(rerank.getModelName()), true, "reranker");
        add(result, "elasticsearch", "Elasticsearch", elasticsearch.isEnabled() && present(elasticsearch.getUrl()), false, "elasticsearch");
        for (var entry : mcp.getServers().entrySet()) {
            String id = "mcp:" + entry.getKey();
            add(result, id, "MCP · " + entry.getKey(), mcp.isEnabled() && present(entry.getValue().getUrl()), false, id);
        }
        return result;
    }

    private void add(List<Status> result, String id, String name, boolean configured, boolean cost, String key) {
        Observation probe = probes.getIfPresent(key);
        result.add(new Status(id, name, configured, cost,
                configured ? (probe == null ? new Observation("UNTESTED", "未检测，配置完整不代表服务可用", null, 0) : probe)
                        : new Observation("NOT_CONFIGURED", "未配置、未启用或未授权", null, 0),
                runtime.getIfPresent(key)));
    }

    public Observation testDraft(long userId, UserModelData.Edit edit) {
        return guarded(userId, "draft:" + userId, () -> {
            var credentials = models.draft(userId, edit);
            sample(models.model(credentials, true));
        }, false);
    }

    public Observation test(long userId, String id) {
        Status status = status(userId).stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException("不支持的依赖检查"));
        if (!status.configured()) throw new BusinessException("该服务尚未配置、启用或授权");
        var personal = models.view(userId);
        String key = id.equals("model") ? modelKey(userId, personal.version(), personal.enabled()) : id;
        return guarded(userId, key, () -> {
            switch (id) {
                case "model" -> {
                    if (personal.enabled()) {
                        sample(models.model(models.draft(userId, new UserModelData.Edit(true, personal.version(),
                                personal.baseUrl(), personal.modelName(), null)), true));
                    } else {
                        AgentBudget.requireConfigured(agent);
                        AgentProperties bounded = new AgentProperties();
                        bounded.setProxyUrl(agent.getProxyUrl());
                        bounded.setBaseUrl(agent.getBaseUrl());
                        bounded.setApiKey(agent.getApiKey());
                        bounded.setModelName(agent.getModelName());
                        bounded.setStreaming(false);
                        bounded.setTimeoutSeconds(12);
                        bounded.setMaxOutputTokens(32);
                        sample(new OpenAiReasoningChatModel(bounded, json));
                    }
                }
                case "embedding" -> {
                    var vector = embeddings.embed(List.of("connection test")).get(0);
                    if (vector.size() != embedding.getDimensions()) throw new Incompatible();
                }
                case "reranker" -> reranker.rerank("connection test", List.of("connection test", "other text"), 2);
                case "milvus" -> vectors.checkHealth();
                case "elasticsearch" -> {
                    HttpUrl url = HttpUrl.get(elasticsearch.getUrl()).newBuilder()
                            .addPathSegment(elasticsearch.getIndex()).build();
                    JsonNode result = request(new Request.Builder().url(url).get().build());
                    if (!result.has(elasticsearch.getIndex())) throw new Incompatible();
                }
                default -> checkMcp(id.substring(4));
            }
        }, true);
    }

    /** 不设置检测冷却时间，只限制全局并发，完成后可立即重试。 */
    private Observation guarded(long userId, String key, Probe probe, boolean cache) {
        if (!slots.tryAcquire()) throw new BusinessException("当前检测任务较多，请稍后重试");
        long start = System.nanoTime();
        Observation result;
        try {
            probe.run();
            result = observation("HEALTHY", "检测成功；不代表完整问答及工具调用已验收", start);
        } catch (Incompatible error) {
            result = observation("INCOMPATIBLE", "响应结构或索引与当前配置不兼容", start);
        } catch (Exception error) {
            // 不回传异常正文、服务地址或密钥。
            String detail = describeFailure(error);
            result = observation("ERROR", detail, start);
        } finally {
            slots.release();
        }

        if (cache) probes.put(key, result);
        return result;
    }

    /** 根据可信异常类型生成诊断，不回传供应商的原始响应。 */
    static String describeFailure(Exception error) {
        if (error instanceof BusinessException) return error.getMessage();
        if (error instanceof HttpError http) return httpFailure(http.code);
        if (error instanceof OpenAiReasoningChatModel.CallFailure failure) {
            if (failure.httpStatus() != null) {
                String summary = httpFailure(failure.httpStatus());
                return failure.diagnostic() == null ? summary : summary + "；供应商详情：" + failure.diagnostic();
            }
            if (failure.diagnostic() != null) return failure.diagnostic();
            return switch (failure.category()) {
                case CONFIGURATION -> "模型配置无效，请检查 Base URL、API Key 和模型名称";
                case TIMEOUT -> "请求超时，服务未在检测时限内完成响应";
                case NETWORK -> "网络连接中断，请检查网络、代理或服务状态";
                case PROTOCOL -> "响应不符合 OpenAI Chat Completions 协议，请检查 Base URL 和接口类型";
                case CANCELLED -> "检测请求已取消";
                default -> "模型请求失败（类别：" + failure.category() + "）";
            };
        }
        if (error instanceof RetrievalException) {
            String message = error.getMessage();
            var match = Pattern.compile("检索服务 HTTP (\\d{3})").matcher(message == null ? "" : message);
            if (match.matches()) return httpFailure(Integer.parseInt(match.group(1)));
            // 检索客户端只生成固定校验消息；未知消息仍不直接展示。
            if (message != null && message.length() <= 100 && !message.contains("http")
                    && (message.startsWith("Milvus：") || message.startsWith("向量化")
                    || message.startsWith("重排") || message.startsWith("检索响应")
                    || message.equals("检索服务请求失败或超时"))) return message;
        }
        if (error instanceof UnknownHostException) return "DNS 解析失败，请检查服务域名";
        if (error instanceof SSLException) return "TLS 证书或 HTTPS 握手失败";
        if (error instanceof ConnectException) return "无法建立连接，请检查服务是否启动及端口配置";
        if (error instanceof InterruptedIOException) return "请求超时，服务未在检测时限内响应";
        if (error instanceof JsonProcessingException) return "服务返回的内容不是有效 JSON，请检查接口地址";
        if (error instanceof IllegalArgumentException) return "服务地址或请求参数配置无效";
        return "检测失败（" + error.getClass().getSimpleName() + "），请联系管理员检查服务日志";
    }

    private static String httpFailure(int status) {
        String reason = switch (status) {
            case 400, 422 -> "请求参数或模型名称不受支持，请检查接口协议及模型标识";
            case 401 -> "认证失败，请检查 API Key 是否正确或已过期";
            case 403 -> "访问被拒绝，请检查密钥权限、模型授权或 IP 限制";
            case 404 -> "接口、模型或索引不存在，请检查 Base URL、模型名称及索引配置";
            case 405 -> "接口不支持此请求方法，请确认填写的是 API 基础地址";
            case 408, 504 -> "远端请求超时，请检查服务负载和网关";
            case 413 -> "远端拒绝请求大小，请检查网关限制";
            case 429 -> "供应商限流或额度不足，请检查用量和并发限制";
            default -> status >= 500 ? "远端服务异常，请检查供应商或网关状态"
                    : status >= 300 && status < 400 ? "接口要求重定向；安全策略禁止自动跳转，请填写最终 API 地址"
                    : "远端拒绝请求，请检查服务配置";
        };
        return "HTTP " + status + "：" + reason;
    }

    private void sample(OpenAiReasoningChatModel model) {
        var response = model.generate(List.of(UserMessage.from("Reply only OK.")));
        if (response == null || response.content() == null || response.content().hasToolExecutionRequests()
                || response.content().text() == null || response.content().text().isBlank()
                || response.finishReason() != FinishReason.STOP) throw new Incompatible();
    }

    private void checkMcp(String name) throws Exception {
        var config = mcp.getServers().get(name);
        Map<String, String> session = new HashMap<>();
        String requestId = UUID.randomUUID().toString();
        JsonNode initialized = rpc(config, session, requestId, "initialize", Map.of("protocolVersion", "2024-11-05",
                "capabilities", Map.of(), "clientInfo", Map.of("name", "TeamDocs-health", "version", "1")));
        if (initialized.has("error") || !initialized.path("result").isObject()) throw new Incompatible();
        String protocol = initialized.path("result").path("protocolVersion").asText();
        if (protocol.isBlank()) throw new Incompatible();
        session.put("MCP-Protocol-Version", protocol);
        rpc(config, session, null, "notifications/initialized", Map.of());
        JsonNode tools = rpc(config, session, UUID.randomUUID().toString(), "tools/list", Map.of());
        if (tools.has("error") || !tools.path("result").path("tools").isArray()) throw new Incompatible();
    }

    private JsonNode rpc(McpProperties.ServerConfig config, Map<String, String> session,
                         String id, String method, Object params) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        if (id != null) payload.put("id", id);
        payload.put("method", method);
        payload.put("params", params);
        Request.Builder request = new Request.Builder().url(config.getUrl())
                .header("Accept", "application/json, text/event-stream")
                .post(RequestBody.create(json.writeValueAsBytes(payload), MediaType.get("application/json")));
        config.getHeaders().forEach((name, value) -> { if (value != null && !value.isBlank()) request.header(name, value); });
        session.forEach(request::header);
        try (Response response = http.newCall(request.build()).execute()) {
            if (!response.isSuccessful()) throw new HttpError(response.code());
            String sessionId = response.header("Mcp-Session-Id");
            if (sessionId != null) session.put("Mcp-Session-Id", sessionId);
            if (id == null) return json.createObjectNode();
            if (response.body() == null) throw new Incompatible();
            byte[] bytes = response.body().byteStream().readNBytes(262145);
            if (bytes.length > 262144) throw new Incompatible();
            JsonNode result;
            try { result = json.readTree(bytes); }
            catch (IOException error) { throw new Incompatible(); }
            if (result == null || !id.equals(result.path("id").asText())) throw new Incompatible();
            return result;
        }
    }

    private JsonNode request(Request request) throws Exception {
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new HttpError(response.code());
            if (response.body() == null) throw new Incompatible();
            byte[] body = response.body().byteStream().readNBytes(262145);
            if (body.length > 262144) throw new Incompatible();
            return json.readTree(body);
        }
    }

    /** 只记录降级类别和时间，不包含查询、正文或用户身份。 */
    public void retrievalResult(String dependency, boolean success) {
        runtime.put(dependency, new Observation(success ? "HEALTHY" : "DEGRADED",
                success ? "最近一次实际调用成功" : "最近一次实际调用失败，已使用关键词或融合排名降级",
                Instant.now().toString(), 0));
    }

    private boolean configuredModel() {
        return agent.isEnabled() && agent.isAllowDocumentEgress() && RetrievalHttp.hasApiKey(agent.getApiKey())
                && present(agent.getBaseUrl()) && present(agent.getModelName());
    }
    private String modelKey(long user, long version, boolean personal) { return personal ? "model:" + user + ":" + version : "model:system"; }
    private boolean present(String text) { return text != null && !text.isBlank(); }
    private Observation observation(String status, String detail, long start) {
        return new Observation(status, detail, Instant.now().toString(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }
    @FunctionalInterface private interface Probe { void run() throws Exception; }
    private static class Incompatible extends RuntimeException { }
    private static class HttpError extends RuntimeException {
        private final int code;
        HttpError(int code) { this.code = code; }
    }
}
