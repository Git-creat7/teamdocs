package asia.creat.agent.model;

import asia.creat.config.AgentProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.InternalOpenAiHelper;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 保留独立推理通道的 OpenAI 协议适配器。 */
public class OpenAiReasoningChatModel implements ChatLanguageModel {
    private static final int MAX_REASONING_CHARS = 32768;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_FRAME_BYTES = 256 * 1024;
    private static final String FAILURE = "模型请求失败";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final AgentProperties properties;
    private final ObjectMapper mapper;
    private final ObjectReader reader;
    private final OkHttpClient client;
    private final HttpUrl endpoint;

    /** 沿用 Agent 的显式配置和调用边界。 */
    public OpenAiReasoningChatModel(AgentProperties properties, ObjectMapper mapper) {
        try {
            if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()
                    || properties.getApiKey() == null || properties.getApiKey().isBlank()
                    || properties.getModelName() == null || properties.getModelName().isBlank()) {
                throw new IllegalArgumentException();
            }
            this.properties = properties;
            this.mapper = mapper;
            this.reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            HttpUrl base = HttpUrl.get(properties.getBaseUrl().trim());
            String path = base.encodedPath().replaceAll("/+$", "");
            this.endpoint = base.newBuilder()
                    .encodedPath((path.isEmpty() ? "/v1" : path) + "/chat/completions").build();
            int seconds = Math.min(90, Math.max(1, properties.getTimeoutSeconds()));
            this.client = new OkHttpClient.Builder()
                    .callTimeout(seconds, TimeUnit.SECONDS)
                    .connectTimeout(seconds, TimeUnit.SECONDS)
                    .readTimeout(seconds, TimeUnit.SECONDS)
                    .writeTimeout(seconds, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .addNetworkInterceptor(chain -> {
                        okhttp3.Response response = chain.proceed(chain.request());
                        // 在 OkHttp 跟进响应前终止，包含 503 Retry-After:0。
                        if (!response.isSuccessful()) {
                            response.close();
                            throw new IOException(FAILURE);
                        }
                        return response;
                    })
                    .build();
        } catch (Exception ignored) {
            throw new RuntimeException(FAILURE);
        }
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return generate(messages, List.of());
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> tools) {
        return generate(messages, tools, null, new RequestControl());
    }

    /** 执行单次请求，推理观察和取消均不改变消息历史。 */
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> tools,
                                        ReasoningObserver observer, RequestControl control) {
        RequestControl requestControl = control == null ? new RequestControl() : control;
        Call call = null;
        try {
            boolean streaming = properties.isStreaming();
            ObjectNode payload = mapper.createObjectNode();
            payload.put("model", properties.getModelName());
            payload.put("max_tokens", Math.min(4096, Math.max(1, properties.getMaxOutputTokens())));
            payload.put("stream", streaming);
            payload.set("messages", mapper.valueToTree(InternalOpenAiHelper.toOpenAiMessages(messages)));
            if (tools != null && !tools.isEmpty()) {
                payload.set("tools", mapper.valueToTree(InternalOpenAiHelper.toTools(tools, false)));
            }
            if (streaming) {
                payload.putObject("stream_options").put("include_usage", true);
            }
            Request request = new Request.Builder().url(endpoint)
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Accept", streaming ? "text/event-stream, application/json" : "application/json")
                    .post(RequestBody.create(mapper.writeValueAsBytes(payload), JSON)).build();
            call = client.newCall(request);
            requestControl.register(call);
            requestControl.checkCancelled();
            try (okhttp3.Response response = call.execute()) {
                ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null) {
                    throw new IOException(FAILURE);
                }
                Completion completion = new Completion(observer, requestControl);
                MediaType contentType = body.contentType();
                if (contentType != null && "application".equals(contentType.type())
                        && "json".equals(contentType.subtype())) {
                    byte[] bytes = body.byteStream().readNBytes(MAX_RESPONSE_BYTES + 1);
                    if (bytes.length > MAX_RESPONSE_BYTES) {
                        throw new IOException(FAILURE);
                    }
                    completion.accept(reader.readTree(bytes), false);
                } else if (streaming && contentType != null && "text".equals(contentType.type())
                        && "event-stream".equals(contentType.subtype())) {
                    readStream(body.byteStream(), completion, requestControl);
                } else {
                    throw new IOException(FAILURE);
                }
                requestControl.checkCancelled();
                return completion.finish();
            }
        } catch (Exception ignored) {
            if (call != null) {
                call.cancel();
            }
            // 不保留 cause，避免异常链携带正文、凭据或 URL 查询参数。
            throw new RuntimeException(FAILURE);
        } finally {
            if (call != null) {
                requestControl.activeCall.compareAndSet(call, null);
            }
        }
    }

    /** 按 SSE 行边界解码，字节和事件均设上限。 */
    private void readStream(InputStream input, Completion completion, RequestControl control) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        StringBuilder data = new StringBuilder();
        int totalBytes = 0;
        int frameBytes = 0;
        boolean previousCr = false;
        boolean firstLine = true;
        int next;
        while ((next = input.read()) != -1) {
            control.checkCancelled();
            if (++totalBytes > MAX_RESPONSE_BYTES || ++frameBytes > MAX_FRAME_BYTES) {
                throw new IOException(FAILURE);
            }
            if (next == '\n' && previousCr) {
                previousCr = false;
                continue;
            }
            previousCr = next == '\r';
            if (next == '\r' || next == '\n') {
                String text = line.toString(StandardCharsets.UTF_8);
                if (firstLine && text.startsWith("﻿")) text = text.substring(1);
                firstLine = false;
                line.reset();
                if (text.isEmpty()) {
                    if (acceptEvent(data, completion)) {
                        return;
                    }
                    data.setLength(0);
                    frameBytes = 0;
                } else {
                    appendData(text, data);
                }
            } else {
                line.write(next);
            }
        }
        // EOF 不是完整结束标志，不能将未闭合尾帧提升为可执行工具调用。
        throw new IOException(FAILURE);
    }

    /** 合并同一事件的多行 data 字段。 */
    private void appendData(String line, StringBuilder data) {
        if (line.equals("data") || line.startsWith("data:")) {
            String value = line.equals("data") ? "" : line.substring(5);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            data.append(value).append('\n');
        }
    }

    /** 仅以 DONE 结束流，不在 finish_reason 处丢弃后置用量。 */
    private boolean acceptEvent(StringBuilder data, Completion completion) throws IOException {
        String value = data.toString().trim();
        if (value.isEmpty()) {
            return false;
        }
        if ("[DONE]".equals(value)) {
            return true;
        }
        completion.accept(reader.readTree(value), true);
        return false;
    }

    /** 读取可缺失的文本字段，拒绝错误类型。 */
    private static String text(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        if (!value.isTextual()) {
            throw new IOException(FAILURE);
        }
        return value.textValue();
    }

    /** 缺失用量保留为空，不补零。 */
    private static Integer tokens(JsonNode usage, String field) throws IOException {
        JsonNode value = usage.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw new IOException(FAILURE);
        }
        return value.intValue();
    }

    @FunctionalInterface
    public interface ReasoningObserver {
        /** 接收本次模型调用的累计推理。 */
        void onReasoning(String cumulativeText, Long durationMs, boolean truncated);
    }

    /** 一个控制对象对应一个在途请求，取消标志不会复位。 */
    public static class RequestControl {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Call> activeCall = new AtomicReference<>();

        /** 兼容取消发生在 Call 注册之前或之后。 */
        public void cancel() {
            cancelled.set(true);
            Call call = activeCall.get();
            if (call != null) {
                call.cancel();
            }
        }

        /** 注册后复查标志，关闭取消竞态窗口。 */
        private void register(Call call) throws IOException {
            if (!activeCall.compareAndSet(null, call)) {
                throw new IOException(FAILURE);
            }
            if (cancelled.get()) {
                call.cancel();
            }
        }

        /** 即使响应已缓冲，也不在取消后返回成功。 */
        private void checkCancelled() throws IOException {
            if (cancelled.get()) {
                throw new IOException(FAILURE);
            }
        }
    }

    private final class Completion {
        private final ReasoningObserver observer;
        private final RequestControl control;
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final Map<Integer, ToolParts> tools = new TreeMap<>();
        private TokenUsage usage;
        private FinishReason finishReason;
        private Long firstReasoningNanos;
        private boolean truncated;
        private boolean hasChoice;

        /** 为每次调用隔离聚合状态。 */
        private Completion(ReasoningObserver observer, RequestControl control) {
            this.observer = observer;
            this.control = control;
        }

        /** 同时支持完整 JSON 和 SSE 增量对象。 */
        private void accept(JsonNode root, boolean streaming) throws IOException {
            control.checkCancelled();
            if (root == null || !root.isObject() || root.hasNonNull("error")) {
                throw new IOException(FAILURE);
            }
            JsonNode reportedUsage = root.get("usage");
            if (reportedUsage != null && !reportedUsage.isNull()) {
                if (!reportedUsage.isObject()) {
                    throw new IOException(FAILURE);
                }
                usage = new TokenUsage(tokens(reportedUsage, "prompt_tokens"),
                        tokens(reportedUsage, "completion_tokens"), tokens(reportedUsage, "total_tokens"));
            }
            JsonNode choices = root.get("choices");
            if (streaming && choices == null && reportedUsage != null && reportedUsage.isObject()) {
                return;
            }
            if (choices == null || !choices.isArray()) {
                throw new IOException(FAILURE);
            }
            for (JsonNode choice : choices) {
                if (choice.hasNonNull("index") && choice.path("index").asInt(-1) != 0) {
                    continue;
                }
                hasChoice = true;
                String reason = text(choice, "finish_reason");
                if (!reason.isEmpty()) {
                    FinishReason mapped = InternalOpenAiHelper.finishReasonFrom(reason);
                    finishReason = mapped == null ? FinishReason.OTHER : mapped;
                }
                JsonNode message = choice.get(streaming ? "delta" : "message");
                if (message == null || message.isNull()) {
                    continue;
                }
                if (!message.isObject()) {
                    throw new IOException(FAILURE);
                }
                appendReasoning(text(message, "reasoning_content"), streaming);
                content.append(text(message, "content"));
                JsonNode calls = message.get("tool_calls");
                if (calls != null && !calls.isNull()) {
                    if (!calls.isArray()) {
                        throw new IOException(FAILURE);
                    }
                    for (int i = 0; i < calls.size(); i++) {
                        JsonNode tool = calls.get(i);
                        JsonNode index = tool.get("index");
                        if (streaming && (index == null || !index.isIntegralNumber()
                                || !index.canConvertToInt() || index.intValue() < 0)) {
                            throw new IOException(FAILURE);
                        }
                        int key = streaming ? index.intValue() : i;
                        tools.computeIfAbsent(key, ignored -> new ToolParts()).append(tool);
                    }
                }
            }
        }

        /** 限制推理留存，耗时仍覆盖截断后的有效分片。 */
        private void appendReasoning(String part, boolean streaming) throws IOException {
            if (part.isEmpty()) {
                return;
            }
            long now = System.nanoTime();
            if (firstReasoningNanos == null) {
                firstReasoningNanos = now;
            }
            if (!truncated) {
                int length = Math.min(MAX_REASONING_CHARS - reasoning.length(), part.length());
                reasoning.append(part, 0, length);
                truncated = reasoning.length() == MAX_REASONING_CHARS;
                if (truncated && Character.isHighSurrogate(reasoning.charAt(reasoning.length() - 1))) {
                    reasoning.setLength(reasoning.length() - 1);
                }
            }
            if (observer != null) {
                int length = reasoning.length();
                // 暂存跨分片的高代理项，但不把半个字符交给观察器。
                if (length > 0 && Character.isHighSurrogate(reasoning.charAt(length - 1))) {
                    length--;
                }
                Long duration = streaming ? TimeUnit.NANOSECONDS.toMillis(now - firstReasoningNanos) : null;
                observer.onReasoning(reasoning.substring(0, length), duration, truncated);
                control.checkCancelled();
            }
        }

        /** 完整接收后才生成可交给工具循环的消息。 */
        private Response<AiMessage> finish() throws IOException {
            if (!hasChoice || finishReason == null) {
                throw new IOException(FAILURE);
            }
            for (ToolParts tool : tools.values()) {
                JsonNode arguments = reader.readTree(tool.arguments.toString());
                if (tool.id.toString().isBlank() || tool.name.toString().isBlank()
                        || arguments == null || !arguments.isObject()) {
                    throw new IOException(FAILURE);
                }
            }
            List<ToolExecutionRequest> requests = new ArrayList<>();
            for (ToolParts tool : tools.values()) {
                requests.add(ToolExecutionRequest.builder().id(tool.id.toString())
                        .name(tool.name.toString()).arguments(tool.arguments.toString()).build());
            }
            AiMessage message;
            if (requests.isEmpty()) {
                message = AiMessage.from(content.toString());
            } else if (content.toString().isBlank()) {
                message = AiMessage.from(requests);
            } else {
                message = AiMessage.from(content.toString(), requests);
            }
            control.checkCancelled();
            return Response.from(message, usage, finishReason);
        }
    }

    private static class ToolParts {
        private final StringBuilder id = new StringBuilder();
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();

        /** 工具索引隔离参数，禁止把交错分片串接到其他工具。 */
        private void append(JsonNode tool) throws IOException {
            String type = text(tool, "type");
            if (!type.isEmpty() && !"function".equals(type)) {
                throw new IOException(FAILURE);
            }
            id.append(text(tool, "id"));
            JsonNode function = tool.get("function");
            if (function != null && !function.isNull()) {
                if (!function.isObject()) {
                    throw new IOException(FAILURE);
                }
                name.append(text(function, "name"));
                arguments.append(text(function, "arguments"));
            }
        }
    }
}
