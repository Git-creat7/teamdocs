package asia.creat.agent.mcp;

import asia.creat.config.McpProperties;
import asia.creat.retrieval.RetrievalHttp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class McpHttpClient {
    private final McpProperties properties;
    private final ObjectMapper mapper;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    // 缓存所有 MCP Server 暴露的工具名及所属 Server
    private final Map<String, McpProperties.ServerConfig> toolToServer = new ConcurrentHashMap<>();
    private final List<ToolSpecification> mcpToolSpecs = new ArrayList<>();
    // 缓存 MCP Server 会话 ID (兼容 Streamable HTTP 协议中 Mcp-Session-Id 响应头)
    private final Map<McpProperties.ServerConfig, String> serverSessions = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        if (!properties.isEnabled() || properties.getServers().isEmpty()) {
            log.info("MCP 功能未启用或无配置 Server");
            return;
        }

        for (var entry : properties.getServers().entrySet()) {
            String serverName = entry.getKey();
            var config = entry.getValue();

            try {
                initializeAndDiscover(serverName, config);
            } catch (Exception e) {
                log.error("MCP Server [{}] 初始化失败: {}", serverName, e.getMessage(), e);
            }
        }
    }

    /**
     * 初始化并发现 MCP 服务器的工具
     *
     * @param serverName 服务器名称
     * @param config     服务器配置
     * @throws IOException 网络异常
     */
    private void initializeAndDiscover(String serverName, McpProperties.ServerConfig config) throws IOException {
        if (config.getUrl() == null || config.getUrl().isBlank()) {
            log.warn("MCP Server [{}] 未配置 URL，跳过", serverName);
            return;
        }

        if (config.getHeaders() != null) {
            String auth = config.getHeaders().get("Authorization");

            if (auth != null && auth.trim().toLowerCase(Locale.ROOT).startsWith("bearer")
                    && !RetrievalHttp.hasApiKey(auth.trim().substring(6).trim())) {
                log.info("MCP Server [{}] 未配置有效 API Token (Authorization 为空)，跳过连接", serverName);
                return;
            }
        }

        log.info("开始连接 MCP Server [{}] -> {}", serverName, config.getUrl());

        // 1. 发送 initialize 握手请求
        Map<String, Object> initPayload = Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "initialize",
                "params", Map.of(
                        "protocolVersion", "2024-11-05",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "TeamDocs", "version", "1.0.0")
                )
        );

        try {
            sendJsonRpc(config, initPayload);

            Map<String, Object> initializedNotification = Map.of(
                    "jsonrpc", "2.0",
                    "method", "notifications/initialized",
                    "params", Map.of()
            );

            sendJsonRpc(config, initializedNotification);
        } catch (Exception e) {
            log.debug("MCP Server [{}] initialize 阶段响应 (可能为无状态端点): {}", serverName, e.getMessage());
        }

        // 2. 发送 tools/list 获取工具定义
        Map<String, Object> listPayload = Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "tools/list",
                "params", Map.of()
        );
        JsonNode response = sendJsonRpc(config, listPayload);
        JsonNode toolsNode = response.path("result").path("tools");

        if (toolsNode.isArray()) {
            for (JsonNode tool : toolsNode) {
                String toolName = tool.path("name").asText();
                String desc = tool.path("description").asText("");

                toolToServer.put(toolName, config);

                // 转换为 LangChain4j 规格
                ToolSpecification.Builder spec = ToolSpecification.builder()
                        .name(toolName)
                        .description(desc);

                // 读取参数 Schema
                JsonNode schemaNode = tool.path("inputSchema");

                if (schemaNode.isObject()) {
                    JsonObjectSchema.Builder objBuilder = JsonObjectSchema.builder();

                    schemaNode.path("properties").fields().forEachRemaining(field -> {
                        String fieldName = field.getKey();
                        JsonNode propDef = field.getValue();
                        String fieldDesc = propDef.path("description").asText("");
                        String type = extractPropertyType(propDef);

                        if ("integer".equalsIgnoreCase(type) || "int".equalsIgnoreCase(type)) {
                            objBuilder.addIntegerProperty(fieldName, fieldDesc);
                        } else if ("number".equalsIgnoreCase(type)) {
                            objBuilder.addNumberProperty(fieldName, fieldDesc);
                        } else if ("boolean".equalsIgnoreCase(type)) {
                            objBuilder.addBooleanProperty(fieldName, fieldDesc);
                        } else if ("array".equalsIgnoreCase(type)) {
                            objBuilder.addProperty(fieldName, JsonArraySchema.builder()
                                    .description(fieldDesc)
                                    .items(new JsonStringSchema())
                                    .build());
                        } else {
                            objBuilder.addStringProperty(fieldName, fieldDesc);
                        }
                    });

                    if (schemaNode.has("required") && schemaNode.path("required").isArray()) {
                        List<String> req = new ArrayList<>();
                        schemaNode.path("required").forEach(r -> req.add(r.asText()));
                        objBuilder.required(req);
                    }

                    spec.parameters(objBuilder.build());
                }

                mcpToolSpecs.add(spec.build());
                log.info("成功注册 MCP 工具: [{}] 来自 Server [{}]", toolName, serverName);
            }
        }
    }

    /**
     * 从属性定义中解析实际类型（兼容 anyOf / oneOf 可空联合类型）
     */
    private String extractPropertyType(JsonNode propDef) {
        if (propDef.has("type") && !propDef.path("type").isNull()) {
            return propDef.path("type").asText("string");
        }
        if (propDef.has("anyOf") && propDef.path("anyOf").isArray()) {
            for (JsonNode option : propDef.path("anyOf")) {
                String t = option.path("type").asText("");
                if (!t.isBlank() && !"null".equalsIgnoreCase(t)) {
                    return t;
                }
            }
        }
        if (propDef.has("oneOf") && propDef.path("oneOf").isArray()) {
            for (JsonNode option : propDef.path("oneOf")) {
                String t = option.path("type").asText("");
                if (!t.isBlank() && !"null".equalsIgnoreCase(t)) {
                    return t;
                }
            }
        }
        return "string";
    }

    /**
     * 调用 MCP 工具
     *
     * @param toolName      工具名称
     * @param argumentsJson 参数 JSON
     * @return 工具执行结果
     */
    public String callTool(String toolName, String argumentsJson) {
        var config = toolToServer.get(toolName);

        if (config == null) {
            throw new IllegalArgumentException("未知的 MCP 工具: " + toolName);
        }

        try {
            JsonNode args = mapper.readTree(argumentsJson);
            Map<String, Object> callPayload = Map.of(
                    "jsonrpc", "2.0",
                    "id", UUID.randomUUID().toString(),
                    "method", "tools/call",
                    "params", Map.of("name", toolName, "arguments", args)
            );
            JsonNode resultNode = sendJsonRpc(config, callPayload);

            if (resultNode.has("error")) {
                log.warn("MCP 工具 [{}] 服务端返回错误: {}", toolName, resultNode.get("error"));
                return resultNode.get("error").toString();
            }

            JsonNode content = resultNode.path("result").path("content");

            if (content.isArray() && !content.isEmpty()) {
                StringBuilder sb = new StringBuilder();

                for (JsonNode item : content) {
                    if ("text".equals(item.path("type").asText()) || item.has("text")) {
                        if (sb.length() > 0) sb.append("\n");

                        sb.append(item.path("text").asText());
                    }
                }

                if (sb.length() > 0) {
                    return sb.toString();
                }
            }

            return mapper.writeValueAsString(resultNode.path("result"));
        } catch (Exception e) {
            log.error("执行 MCP 工具 [{}] 异常: {}", toolName, e.getMessage(), e);
            String msg = e.getMessage();
            if (msg != null && msg.contains("429")) {
                return "{\"error\":\"MCP 服务触发频次限流 (HTTP 429)。该外部服务短时间内不可用，严禁在本轮重复调用该工具！请直接使用已有搜索结果作答，或向用户说明外部检索受限。\"}";
            }
            return "{\"error\":\"" + (msg != null ? msg.replace("\"", "\\\"") : "unknown") + "\"}";
        }
    }

    public boolean hasTool(String toolName) {
        return toolToServer.containsKey(toolName);
    }

    public List<ToolSpecification> getToolSpecifications() {
        return Collections.unmodifiableList(mcpToolSpecs);
    }

    /**
     * 发送 JSON-RPC 请求（遇到 429 限流时自动等待 1 秒并重试一次）
     *
     * @param config  MCP 服务器配置
     * @param payload 请求负载
     * @return 响应结果
     * @throws IOException 网络异常
     */
    private JsonNode sendJsonRpc(McpProperties.ServerConfig config, Object payload) throws IOException {
        Response initialRes = executeHttpCall(config, payload);
        Response finalRes = initialRes;
        if (initialRes.code() == 429) {
            initialRes.close();
            log.warn("MCP 服务返回 HTTP 429 限流，等待 1 秒后自动重试一次: url={}", config.getUrl());
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            finalRes = executeHttpCall(config, payload);
        }

        try (Response res = finalRes) {
            if (!res.isSuccessful() || res.body() == null) {
                String errorBody = "";
                try {
                    if (res.body() != null) {
                        errorBody = res.body().string();
                    }
                } catch (Exception ignored) {
                }
                throw new IOException("MCP 请求失败 HTTP " + res.code() + (errorBody.isBlank() ? "" : ": " + errorBody));
            }

            String respSessionId = res.header("Mcp-Session-Id");
            if (respSessionId == null) {
                respSessionId = res.header("mcp-session-id");
            }
            if (respSessionId != null && !respSessionId.isBlank()) {
                serverSessions.put(config, respSessionId);
            }

            return parseJsonRpcResponse(res.body().string());
        }
    }

    private Response executeHttpCall(McpProperties.ServerConfig config, Object payload) throws IOException {
        String json = mapper.writeValueAsString(payload);
        var reqBuilder = new Request.Builder()
                .url(config.getUrl())
                .header("Accept", "application/json, text/event-stream")
                .post(RequestBody.create(json, MediaType.parse("application/json; charset=utf-8")));

        String sessionId = serverSessions.get(config);
        if (sessionId != null && !sessionId.isBlank()) {
            reqBuilder.header("Mcp-Session-Id", sessionId);
        }

        if (config.getHeaders() != null) {
            config.getHeaders().forEach((k, v) -> {
                if (k != null && v != null && !v.isBlank()) {
                    reqBuilder.addHeader(k, v);
                }
            });
        }

        return client.newCall(reqBuilder.build()).execute();
    }

    /**
     * 解析 JSON-RPC 响应体（兼容普通 JSON 与 SSE text/event-stream 载荷）
     */
    JsonNode parseJsonRpcResponse(String body) throws IOException {
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return mapper.readTree(trimmed);
        }

        // 兼容 SSE (text/event-stream) 格式传输 (如 Tavily 等标准 MCP Server)
        JsonNode candidate = null;
        for (String line : trimmed.split("\r?\n")) {
            String l = line.trim();
            if (l.startsWith("data:")) {
                String dataContent = l.substring(5).trim();
                if (!dataContent.isBlank() && (dataContent.startsWith("{") || dataContent.startsWith("["))) {
                    try {
                        JsonNode parsed = mapper.readTree(dataContent);
                        if (parsed.has("result") || parsed.has("error") || parsed.has("id")) {
                            return parsed;
                        }
                        candidate = parsed;
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        if (candidate != null) {
            return candidate;
        }

        return mapper.readTree(trimmed);
    }
}
