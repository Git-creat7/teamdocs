package asia.creat.teamdocsbackend.agent;

import asia.creat.agent.model.OpenAiReasoningChatModel;
import asia.creat.agent.model.OpenAiReasoningChatModel.RequestControl;
import asia.creat.config.AgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class OpenAiReasoningChatModelTest {
    private static final List<ChatMessage> MESSAGES = List.of(UserMessage.from("问题"));
    private static final String DONE = "data: [DONE]\n\n";
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<HttpHandler> handler = new AtomicReference<>();
    private HttpServer server;
    private ExecutorService serverExecutor;
    private AgentProperties properties;

    /** 每个测试仅使用本机临时端口。 */
    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/", exchange -> {
            try {
                requests.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        mapper.readTree(exchange.getRequestBody())));
                handler.get().handle(exchange);
            } finally {
                exchange.close();
            }
        });
        handler.set(exchange -> reply(exchange, "application/json", json("回答", null, "stop", null), 0));
        server.start();
        properties = new AgentProperties();
        properties.setBaseUrl(baseUrl() + "/gateway/v1/");
        properties.setApiKey("test-secret-key");
        properties.setModelName("explicit-model");
        properties.setStreaming(true);
        properties.setTimeoutSeconds(3);
    }

    /** 释放测试服务器和工作线程。 */
    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    /** 覆盖多行 SSE、中文和四字节字符的网络分片。 */
    @Test
    void streamsUnicodeMultilineDataAndTrailingUsage() throws Exception {
        String stream = ": heartbeat\r\nevent: message\r\n"
                + "data: {\"choices\":[{\"index\":0,\r\n"
                + "data: \"delta\":{\"reasoning_content\":\"分析\\uD83D\\uDE00\"}}]}\r\n\r\n"
                + delta(Map.of("reasoning_content", "中文\uD83D\uDE03"))
                + delta(Map.of("content", "正文\uD83D\uDE00")) + finish("stop")
                + usage(11, 7, 18) + DONE;
        handler.set(exchange -> reply(exchange, "text/event-stream; charset=utf-8", stream, 1));
        List<Observation> observed = new ArrayList<>();

        Response<AiMessage> response = model().generate(MESSAGES, List.of(),
                (text, duration, truncated) -> observed.add(new Observation(text, duration, truncated)), new RequestControl());

        assertEquals("正文\uD83D\uDE00", response.content().text());
        assertEquals(FinishReason.STOP, response.finishReason());
        assertEquals(11, response.tokenUsage().inputTokenCount());
        assertEquals(7, response.tokenUsage().outputTokenCount());
        assertEquals(18, response.tokenUsage().totalTokenCount());
        assertEquals(List.of("分析\uD83D\uDE00", "分析\uD83D\uDE00中文\uD83D\uDE03"),
                observed.stream().map(Observation::text).toList());
        assertEquals(0L, observed.get(0).duration());
        assertTrue(observed.get(1).duration() >= 0);
        assertFalse(observed.get(1).truncated());
        assertEquals(1, requests.size());
        CapturedRequest request = requests.get(0);
        assertEquals("POST", request.method());
        assertEquals("/gateway/v1/chat/completions", request.path());
        assertEquals("Bearer test-secret-key", request.authorization());
        assertEquals("explicit-model", request.body().path("model").asText());
        assertTrue(request.body().path("stream").asBoolean());
        assertTrue(request.body().path("stream_options").path("include_usage").asBoolean());
    }

    /** 同步 JSON 的推理不虚构时长，关闭流式时不发送流选项。 */
    @Test
    void parsesOrdinaryJsonWithNullDuration() throws Exception {
        properties.setStreaming(false);
        handler.set(exchange -> reply(exchange, "application/json", json("正文", "私有推理", "length",
                Map.of("prompt_tokens", 3, "completion_tokens", 5, "total_tokens", 8)), 2));
        List<Observation> observed = new ArrayList<>();

        Response<AiMessage> result = model().generate(MESSAGES, List.of(),
                (text, duration, truncated) -> observed.add(new Observation(text, duration, truncated)), new RequestControl());

        assertEquals("正文", result.content().text());
        assertEquals(FinishReason.LENGTH, result.finishReason());
        assertEquals(3, result.tokenUsage().inputTokenCount());
        assertEquals(5, result.tokenUsage().outputTokenCount());
        assertEquals(List.of(new Observation("私有推理", null, false)), observed);
        assertFalse(requests.get(0).body().path("stream").asBoolean());
        assertFalse(requests.get(0).body().has("stream_options"));
    }

    /** 网关直接返回 JSON 时使用同一次响应，不重新请求。 */
    @Test
    void acceptsJsonResponseToStreamingRequestWithoutReplay() {
        assertEquals("回答", model().generate(MESSAGES).content().text());
        assertTrue(requests.get(0).body().path("stream").asBoolean());
        assertEquals(1, requests.size());
    }

    /** 无推理时保持静默，普通重载也返回完整消息。 */
    @Test
    void worksWithoutReasoningOrObserver() throws Exception {
        String stream = delta(Map.of("reasoning_content", "")) + delta(Map.of("content", "答案"))
                + finish("stop") + DONE;
        handler.set(exchange -> reply(exchange, "text/event-stream", stream, 0));
        OpenAiReasoningChatModel model = model();
        Response<AiMessage> result = model.generate(MESSAGES, List.of(),
                (text, duration, truncated) -> fail("不应收到推理回调"), new RequestControl());
        assertEquals("答案", result.content().text());
        assertNull(result.tokenUsage());
        assertEquals("答案", model.generate(MESSAGES).content().text());
        assertEquals("答案", model.generate(MESSAGES, List.of()).content().text());
    }

    /** 推理存在时，不带观察器的重载仍完整读取正文和用量。 */
    @Test
    void drainsReasoningWhenObserverIsAbsent() throws Exception {
        String stream = delta(Map.of("reasoning_content", "不写入答案")) + delta(Map.of("content", "答案"))
                + finish("stop") + usage(5, 9, 14) + DONE;
        handler.set(exchange -> reply(exchange, "text/event-stream", stream, 0));
        Response<AiMessage> result = model().generate(MESSAGES, List.of());
        assertEquals("答案", result.content().text());
        assertEquals(9, result.tokenUsage().outputTokenCount());
    }

    /** 交错工具按索引排序，参数全部接收后才交给调用方。 */
    @Test
    void assemblesInterleavedToolArgumentsAndMapsHistory() throws Exception {
        String stream = delta(Map.of("tool_calls", List.of(tool(1, "call-b", "read", "{\"id\":"))))
                + delta(Map.of("tool_calls", List.of(tool(0, "call-a", "search", "{\"q\":\"中"))))
                + delta(Map.of("tool_calls", List.of(tool(1, null, null, "7}"), tool(0, null, null, "文\"}"))))
                + finish("tool_calls") + usage(13, 4, 17) + DONE;
        handler.set(exchange -> reply(exchange, "text/event-stream", stream, 3));
        ToolExecutionRequest earlier = ToolExecutionRequest.builder().id("previous-call").name("search")
                .arguments("{\"q\":\"旧\"}").build();
        List<ChatMessage> history = List.of(SystemMessage.from("系统"), UserMessage.from("用户"),
                AiMessage.from(earlier), ToolExecutionResultMessage.from(earlier, "工具结果"));
        ToolSpecification spec = ToolSpecification.builder().name("search").description("搜索")
                .parameters(JsonObjectSchema.builder().addStringProperty("q").required("q").build()).build();

        Response<AiMessage> response = model().generate(history, List.of(spec));

        assertNull(response.content().text());
        assertEquals(FinishReason.TOOL_EXECUTION, response.finishReason());
        List<ToolExecutionRequest> tools = response.content().toolExecutionRequests();
        assertEquals(2, tools.size());
        assertEquals("call-a", tools.get(0).id());
        assertEquals("search", tools.get(0).name());
        assertEquals("{\"q\":\"中文\"}", tools.get(0).arguments());
        assertEquals("call-b", tools.get(1).id());
        assertEquals("{\"id\":7}", tools.get(1).arguments());
        assertEquals(13, response.tokenUsage().inputTokenCount());
        JsonNode request = requests.get(0).body();
        assertEquals("system", request.at("/messages/0/role").asText());
        assertEquals("user", request.at("/messages/1/role").asText());
        assertEquals("assistant", request.at("/messages/2/role").asText());
        assertEquals("previous-call", request.at("/messages/2/tool_calls/0/id").asText());
        assertEquals("tool", request.at("/messages/3/role").asText());
        assertEquals("previous-call", request.at("/messages/3/tool_call_id").asText());
        assertEquals("工具结果", request.at("/messages/3/content").asText());
        assertEquals("function", request.at("/tools/0/type").asText());
        assertEquals("search", request.at("/tools/0/function/name").asText());
        assertEquals("string", request.at("/tools/0/function/parameters/properties/q/type").asText());
        assertEquals("q", request.at("/tools/0/function/parameters/required/0").asText());
    }

    /** JSON 同时保留正文与工具调用，但不保留推理字段。 */
    @Test
    void parsesJsonToolCalls() throws Exception {
        properties.setStreaming(false);
        String body = mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message",
                Map.of("content", "先查文档", "reasoning_content", "内部分析", "tool_calls",
                        List.of(Map.of("id", "call-json", "type", "function", "function",
                                Map.of("name", "search", "arguments", "{\"q\":\"文档\"}")))),
                "finish_reason", "tool_calls"))));
        handler.set(exchange -> reply(exchange, "application/json", body, 0));
        Response<AiMessage> response = model().generate(MESSAGES);
        assertEquals("先查文档", response.content().text());
        assertEquals("call-json", response.content().toolExecutionRequests().get(0).id());
        assertEquals("{\"q\":\"文档\"}", response.content().toolExecutionRequests().get(0).arguments());
        assertNull(response.tokenUsage());
    }

    /** 后置用量帧省略 choices 时也不能丢失计数。 */
    @Test
    void acceptsUsageOnlyFrameAfterFinish() throws Exception {
        String stream = delta(Map.of("content", "答案")) + finish("stop")
                + "data: {\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3}}\n\n" + DONE;
        handler.set(exchange -> reply(exchange, "text/event-stream", stream, 0));
        Response<AiMessage> response = model().generate(MESSAGES);
        assertEquals(2, response.tokenUsage().inputTokenCount());
        assertEquals(3, response.tokenUsage().outputTokenCount());
        assertNull(response.tokenUsage().totalTokenCount());
    }

    /** 缺失计数字段保留 null，合法零值仍保留零。 */
    @Test
    void preservesPartialUsageWithoutGuessing() throws Exception {
        properties.setStreaming(false);
        handler.set(exchange -> reply(exchange, "application/json", json("回答", null, "stop",
                Map.of("prompt_tokens", 0)), 0));
        Response<AiMessage> response = model().generate(MESSAGES);
        assertEquals(0, response.tokenUsage().inputTokenCount());
        assertNull(response.tokenUsage().outputTokenCount());
        assertNull(response.tokenUsage().totalTokenCount());
    }

    /** 跨调用重置推理累计，并禁止推理进入后续请求历史。 */
    @Test
    void neverSendsReasoningBackInHistory() throws Exception {
        String stream = delta(Map.of("reasoning_content", "独立推理")) + delta(Map.of("content", "公开回答"))
                + finish("stop") + DONE;
        handler.set(exchange -> reply(exchange, "text/event-stream", stream, 0));
        List<String> observed = new ArrayList<>();
        OpenAiReasoningChatModel model = model();
        RequestControl control = new RequestControl();
        Response<AiMessage> first = model.generate(MESSAGES, List.of(),
                (text, duration, truncated) -> observed.add(text), control);
        model.generate(List.of(MESSAGES.get(0), first.content(), UserMessage.from("追问")), List.of(),
                (text, duration, truncated) -> observed.add(text), control);
        assertEquals(List.of("独立推理", "独立推理"), observed);
        assertFalse(requests.get(1).body().toString().contains("独立推理"));
        assertFalse(requests.get(1).body().toString().contains("reasoning_content"));
        assertEquals("公开回答", requests.get(1).body().at("/messages/1/content").asText());
    }

    /** 达到留存上限后继续读取正文与用量，且不切断代理对。 */
    @Test
    void truncatesReasoningSafelyAndDrainsRemainingFrames() throws Exception {
        String prefix = "a".repeat(32767);
        String stream = delta(Map.of("reasoning_content", prefix + "\uD83D\uDE00后续"))
                + delta(Map.of("reasoning_content", "仍在推理")) + delta(Map.of("content", "完整正文"))
                + finish("stop") + usage(8, 10, 18) + DONE;
        handler.set(exchange -> reply(exchange, "text/event-stream", stream, 0));
        List<Observation> observed = new ArrayList<>();
        Response<AiMessage> result = model().generate(MESSAGES, List.of(),
                (text, duration, truncated) -> observed.add(new Observation(text, duration, truncated)), new RequestControl());
        assertEquals(2, observed.size());
        assertEquals(prefix, observed.get(0).text());
        assertEquals(prefix, observed.get(1).text());
        assertTrue(observed.stream().allMatch(Observation::truncated));
        assertTrue(observed.get(1).duration() >= observed.get(0).duration());
        assertEquals("完整正文", result.content().text());
        assertEquals(10, result.tokenUsage().outputTokenCount());
    }

    /** 普通 JSON 也使用相同推理留存边界。 */
    @Test
    void boundsJsonReasoningAtExactLimit() throws Exception {
        properties.setStreaming(false);
        String reasoning = "a".repeat(32766) + "\uD83D\uDE00";
        handler.set(exchange -> reply(exchange, "application/json", json("答案", reasoning, "stop", null), 0));
        List<Observation> observed = new ArrayList<>();
        model().generate(MESSAGES, List.of(),
                (text, duration, truncated) -> observed.add(new Observation(text, duration, truncated)), new RequestControl());
        assertEquals(List.of(new Observation(reasoning, null, true)), observed);
    }

    /** 转义代理对跨事件时只向观察器发送完整字符。 */
    @Test
    void buffersSurrogatePairsSplitAcrossReasoningEvents() throws Exception {
        String stream = "data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"\\uD83D\"}}]}\n\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"\\uDE00\"}}]}\n\n"
                + delta(Map.of("content", "答案")) + finish("stop") + DONE;
        handler.set(exchange -> reply(exchange, "text/event-stream", stream, 0));
        List<String> observed = new ArrayList<>();
        model().generate(MESSAGES, List.of(), (text, duration, truncated) -> observed.add(text), new RequestControl());
        assertEquals(List.of("", "\uD83D\uDE00"), observed);
    }

    /** 支持标准结束原因，未知原因保留为 OTHER。 */
    @ParameterizedTest
    @CsvSource({"stop,STOP", "length,LENGTH", "content_filter,CONTENT_FILTER", "tool_calls,TOOL_EXECUTION", "custom,OTHER"})
    void mapsFinishReasons(String wire, FinishReason expected) throws Exception {
        handler.set(exchange -> reply(exchange, "application/json", json("回答", null, wire, null), 0));
        assertEquals(expected, model().generate(MESSAGES).finishReason());
    }

    /** HTTP 错误和重定向只发生一次，异常不携带响应或查询参数。 */
    @ParameterizedTest
    @ValueSource(ints = {301, 302, 307, 308, 401, 408, 429, 500, 503})
    void sanitizesHttpFailuresAndNeverRetriesOrRedirects(int status) {
        properties.setBaseUrl(baseUrl() + "/v1?secret=query-secret");
        handler.set(exchange -> {
            if (requests.size() > 1) {
                reply(exchange, "application/json", json("不应重试", null, "stop", null), 0);
                return;
            }
            exchange.getResponseHeaders().set("Location", baseUrl() + "/redirected");
            exchange.getResponseHeaders().set("Retry-After", "0");
            byte[] bytes = "provider-body-secret test-secret-key query-secret".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        });
        assertSanitized(assertThrows(RuntimeException.class, () -> model().generate(MESSAGES)));
        assertEquals(1, requests.size());
    }

    /** 畸形 JSON、供应商错误和未完成流均不得回退重放。 */
    @ParameterizedTest
    @ValueSource(strings = {
            "data: {broken provider-body-secret}\n\n",
            "data: {\"error\":{\"message\":\"test-secret-key\"}}\n\n",
            "data: {\"choices\":[{\"delta\":{\"content\":\"未完成\"}}]}\n\ndata: [DONE]\n\n",
            "data: {\"choices\":[{\"delta\":{\"content\":\"中途断开\"}}]}\n\n"
    })
    void rejectsBadOrIncompleteStreamsWithoutReplay(String stream) {
        handler.set(exchange -> reply(exchange, "text/event-stream", stream, 0));
        assertSanitized(assertThrows(RuntimeException.class, () -> model().generate(MESSAGES)));
        assertEquals(1, requests.size());
    }

    /** 合法 UTF-8 BOM 不能使首帧正文或工具标识被丢弃。 */
    @Test
    void acceptsBomBeforeFirstSseFrame() throws Exception {
        String stream = "﻿" + delta(Map.of("content", "你")) + delta(Map.of("content", "好"))
                + finish("stop") + DONE;
        handler.set(exchange -> reply(exchange, "text/event-stream", stream, 1));
        assertEquals("你好", model().generate(MESSAGES).content().text());
        String tools = "﻿" + delta(Map.of("tool_calls", List.of(tool(0, "bom-call", "search", "{}"))))
                + finish("tool_calls") + DONE;
        handler.set(exchange -> reply(exchange, "text/event-stream", tools, 1));
        assertEquals("bom-call", model().generate(MESSAGES).content().toolExecutionRequests().get(0).id());
    }

    /** EOF 不能替代帧闭合和 DONE，不能返回看似完整的工具调用。 */
    @ParameterizedTest
    @ValueSource(strings = {"", "\n", "\n\n"})
    void rejectsEofAfterFinishReasonWithoutDone(String ending) throws Exception {
        String stream = delta(Map.of("tool_calls", List.of(tool(0, "call-a", "search", "{}"))))
                + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}" + ending;
        handler.set(exchange -> reply(exchange, "text/event-stream", stream, 0));
        assertSanitized(assertThrows(RuntimeException.class, () -> model().generate(MESSAGES)));
        assertEquals(1, requests.size());
    }

    /** 完整结束前拒绝不完整工具参数，不能返回可执行调用。 */
    @Test
    void rejectsIncompleteToolArgumentsEvenWithFinishReason() throws Exception {
        String stream = delta(Map.of("tool_calls", List.of(tool(0, "call-a", "search", "{\"q\":"))))
                + finish("tool_calls") + DONE;
        handler.set(exchange -> reply(exchange, "text/event-stream", stream, 0));
        assertSanitized(assertThrows(RuntimeException.class, () -> model().generate(MESSAGES)));
        assertEquals(1, requests.size());
    }

    /** 不接受 HTML 或畸形 JSON，并保留统一脱敏错误。 */
    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/html"})
    void rejectsUnexpectedResponseBodies(String contentType) {
        handler.set(exchange -> reply(exchange, contentType, "provider-body-secret", 0));
        assertSanitized(assertThrows(RuntimeException.class, () -> model().generate(MESSAGES)));
        assertEquals(1, requests.size());
    }

    /** 包括注释和多行 data 在内的单帧均有字节上限。 */
    @Test
    void rejectsOversizedFrame() {
        String stream = ":" + "x".repeat(256 * 1024) + "\n\n";
        handler.set(exchange -> reply(exchange, "text/event-stream", stream, 0));
        assertSanitized(assertThrows(RuntimeException.class, () -> model().generate(MESSAGES)));
        assertEquals(1, requests.size());
    }

    /** 多个合法大小事件仍受整体响应上限限制。 */
    @Test
    void rejectsOversizedStreamAcrossFrames() {
        String stream = (":" + "x".repeat(32 * 1024) + "\n\n").repeat(65);
        handler.set(exchange -> reply(exchange, "text/event-stream", stream, 0));
        assertSanitized(assertThrows(RuntimeException.class, () -> model().generate(MESSAGES)));
        assertEquals(1, requests.size());
    }

    /** JSON 在解析前限制整体响应大小。 */
    @Test
    void rejectsOversizedJson() throws Exception {
        String json = json("x".repeat(2 * 1024 * 1024), null, "stop", null);
        handler.set(exchange -> reply(exchange, "application/json", json, 0));
        assertSanitized(assertThrows(RuntimeException.class, () -> model().generate(MESSAGES)));
        assertEquals(1, requests.size());
    }

    /** 注册前已取消时不能触达服务器。 */
    @Test
    void cancelsBeforeCallRegistration() {
        RequestControl control = new RequestControl();
        control.cancel();
        control.cancel();
        OpenAiReasoningChatModel model = model();
        assertSanitized(assertThrows(RuntimeException.class,
                () -> model.generate(MESSAGES, List.of(), null, control)));
        assertTrue(requests.isEmpty());
    }

    /** 正在等待下个分片时，取消须中断网络读取而非等待超时。 */
    @Test
    void cancelsInFlightRead() throws Exception {
        properties.setTimeoutSeconds(90);
        CountDownLatch observed = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        String firstFrame = delta(Map.of("reasoning_content", "开始分析"));
        handler.set(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(firstFrame.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            awaitServer(releaseServer);
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        RequestControl control = new RequestControl();
        OpenAiReasoningChatModel model = model();
        try {
            Future<Response<AiMessage>> future = worker.submit(() -> model.generate(MESSAGES, List.of(),
                    (text, duration, truncated) -> observed.countDown(), control));
            assertTrue(observed.await(3, TimeUnit.SECONDS));
            control.cancel();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
            assertSanitized((RuntimeException) failure.getCause());
            assertEquals(1, requests.size());
        } finally {
            releaseServer.countDown();
            worker.shutdownNow();
        }
    }

    /** 观察器异常须立即终止请求，不能等服务器完成或泄露异常正文。 */
    @Test
    void abortsWhenObserverThrows() throws Exception {
        properties.setTimeoutSeconds(90);
        CountDownLatch releaseServer = new CountDownLatch(1);
        String firstFrame = delta(Map.of("reasoning_content", "开始"));
        handler.set(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(firstFrame.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            awaitServer(releaseServer);
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        RequestControl control = new RequestControl();
        OpenAiReasoningChatModel model = model();
        try {
            Future<Response<AiMessage>> future = worker.submit(() -> model.generate(MESSAGES, List.of(),
                    (text, duration, truncated) -> { throw new IllegalStateException("provider-body-secret"); }, control));
            ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
            assertSanitized((RuntimeException) failure.getCause());
            assertEquals(1, requests.size());
            releaseServer.countDown();
            handler.set(exchange -> reply(exchange, "application/json", json("回答", null, "stop", null), 0));
            // 异常退出也必须清理旧 Call 引用，后续注册不受影响。
            assertEquals("回答", model.generate(MESSAGES, List.of(), null, control).content().text());
        } finally {
            releaseServer.countDown();
            worker.shutdownNow();
        }
    }

    /** 观察器内取消也不能返回已缓冲的成功结果。 */
    @Test
    void honorsCancellationFromObserver() throws Exception {
        String stream = delta(Map.of("reasoning_content", "分析")) + delta(Map.of("content", "答案"))
                + finish("stop") + DONE;
        handler.set(exchange -> reply(exchange, "text/event-stream", stream, 0));
        RequestControl control = new RequestControl();
        assertSanitized(assertThrows(RuntimeException.class, () -> model().generate(MESSAGES, List.of(),
                (text, duration, truncated) -> control.cancel(), control)));
        assertEquals(1, requests.size());
    }

    /** 根路径补标准前缀，并沿用最大输出边界。 */
    @ParameterizedTest
    @CsvSource({"-1,1", "99999,4096"})
    void usesRootUrlAndTokenBounds(int configured, int expected) {
        properties.setBaseUrl(baseUrl() + "/");
        properties.setMaxOutputTokens(configured);
        model().generate(MESSAGES);
        assertEquals("/v1/chat/completions", requests.get(0).path());
        assertEquals(expected, requests.get(0).body().path("max_tokens").asInt());
    }

    /** 连接在响应头之前关闭也不能触发传输层重试。 */
    @Test
    void neverRetriesDisconnectedTransport() {
        handler.set(HttpExchange::close);
        assertSanitized(assertThrows(RuntimeException.class, () -> model().generate(MESSAGES)));
        assertEquals(1, requests.size());
    }

    /** 零超时配置仍限制为一秒，不能变成无限等待。 */
    @Test
    void clampsZeroTimeoutToOneSecond() throws Exception {
        properties.setTimeoutSeconds(0);
        CountDownLatch releaseServer = new CountDownLatch(1);
        handler.set(exchange -> awaitServer(releaseServer));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        OpenAiReasoningChatModel model = model();
        try {
            Future<Response<AiMessage>> future = worker.submit(() -> model.generate(MESSAGES));
            ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
            assertSanitized((RuntimeException) failure.getCause());
            assertEquals(1, requests.size());
        } finally {
            releaseServer.countDown();
            worker.shutdownNow();
        }
    }

    /** 过大的超时配置在构造 HTTP 客户端前收敛。 */
    @Test
    void clampsExcessiveTimeout() {
        properties.setTimeoutSeconds(Integer.MAX_VALUE);
        assertEquals("回答", model().generate(MESSAGES).content().text());
    }

    /** 空配置不允许使用 SDK 的默认供应商或默认凭据。 */
    @Test
    void requiresExplicitConfiguration() {
        assertSanitized(assertThrows(RuntimeException.class,
                () -> new OpenAiReasoningChatModel(new AgentProperties(), mapper)));
        properties.setBaseUrl("not a url?test-secret-key");
        assertSanitized(assertThrows(RuntimeException.class, this::model));
        assertTrue(requests.isEmpty());
    }

    /** 构建本次测试的模型。 */
    private OpenAiReasoningChatModel model() {
        return new OpenAiReasoningChatModel(properties, mapper);
    }

    /** 返回临时服务器的显式地址。 */
    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 构建普通聊天响应。 */
    private String json(String content, String reasoning, String finish, Map<String, Integer> usage) throws IOException {
        var root = mapper.createObjectNode();
        var choice = root.putArray("choices").addObject();
        choice.put("index", 0).put("finish_reason", finish);
        var message = choice.putObject("message").put("role", "assistant").put("content", content);
        if (reasoning != null) {
            message.put("reasoning_content", reasoning);
        }
        if (usage != null) {
            root.set("usage", mapper.valueToTree(usage));
        }
        return mapper.writeValueAsString(root);
    }

    /** 构建单个 SSE 增量事件。 */
    private String delta(Map<String, ?> fields) throws IOException {
        return "data: " + mapper.writeValueAsString(Map.of("choices", List.of(Map.of("index", 0, "delta", fields)))) + "\n\n";
    }

    /** 构建结束原因事件。 */
    private String finish(String reason) throws IOException {
        return "data: " + mapper.writeValueAsString(Map.of("choices",
                List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", reason)))) + "\n\n";
    }

    /** 构建 finish_reason 之后的独立用量事件。 */
    private String usage(int input, int output, int total) throws IOException {
        return "data: " + mapper.writeValueAsString(Map.of("choices", List.of(), "usage",
                Map.of("prompt_tokens", input, "completion_tokens", output, "total_tokens", total))) + "\n\n";
    }

    /** 生成工具参数分片，首片之外允许省略标识和名称。 */
    private JsonNode tool(int index, String id, String name, String arguments) {
        var tool = mapper.createObjectNode().put("index", index);
        if (id != null) {
            tool.put("id", id).put("type", "function");
        }
        var function = tool.putObject("function").put("arguments", arguments);
        if (name != null) {
            function.put("name", name);
        }
        return tool;
    }

    /** 用可控字节边界写响应，模拟 UTF-8 网络分片。 */
    private void reply(HttpExchange exchange, String type, String body, int chunkBytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, chunkBytes > 0 ? 0 : bytes.length);
        int step = chunkBytes > 0 ? chunkBytes : bytes.length;
        for (int offset = 0; offset < bytes.length; offset += step) {
            exchange.getResponseBody().write(bytes, offset, Math.min(step, bytes.length - offset));
            exchange.getResponseBody().flush();
        }
    }

    /** 保持服务器流打开，交由测试取消请求。 */
    private void awaitServer(CountDownLatch release) throws IOException {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IOException("测试服务器等待超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("测试服务器已停止");
        }
    }

    /** 异常消息及异常链都不能包含供应商资料。 */
    private void assertSanitized(RuntimeException error) {
        assertEquals("模型请求失败", error.getMessage());
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
        assertFalse(error.toString().contains("test-secret-key"));
        assertFalse(error.toString().contains("query-secret"));
        assertFalse(error.toString().contains("provider-body-secret"));
    }

    private record CapturedRequest(String method, String path, String authorization, JsonNode body) { }
    private record Observation(String text, Long duration, boolean truncated) { }
}
