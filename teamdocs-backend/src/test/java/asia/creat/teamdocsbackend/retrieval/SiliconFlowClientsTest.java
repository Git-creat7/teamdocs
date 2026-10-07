package asia.creat.teamdocsbackend.retrieval;

import asia.creat.config.EmbeddingProperties;
import asia.creat.config.RerankProperties;
import asia.creat.retrieval.RetrievalException;
import asia.creat.retrieval.RetrievalHttp;
import asia.creat.retrieval.SiliconFlowEmbeddingClient;
import asia.creat.retrieval.SiliconFlowRerankClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SiliconFlowClientsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private ExecutorService executor;
    private EmbeddingProperties embeddingProperties;
    private RerankProperties rerankProperties;
    private RetrievalHttp http;
    private SiliconFlowEmbeddingClient embedding;
    private SiliconFlowRerankClient reranker;
    private volatile String response = "{}";
    private volatile int status = 200;
    private volatile boolean chunked;
    private volatile CountDownLatch responseGate = new CountDownLatch(0);
    private volatile JsonNode received;
    private volatile String receivedPath;
    private volatile String authorization;

    /** 创建仅监听本机的接口夹具。 */
    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::respond);
        server.start();
        http = new RetrievalHttp(mapper);
        embeddingProperties = new EmbeddingProperties();
        embeddingProperties.setEnabled(true);
        embeddingProperties.setAllowDocumentEgress(true);
        embeddingProperties.setApiKey("test-key");
        embeddingProperties.setBaseUrl(baseUrl());
        embeddingProperties.setDimensions(64);
        embeddingProperties.setModelName("Qwen/Qwen3-Embedding-8B");
        rerankProperties = new RerankProperties();
        rerankProperties.setEnabled(true);
        rerankProperties.setAllowDocumentEgress(true);
        rerankProperties.setApiKey("test-key");
        rerankProperties.setBaseUrl(baseUrl());
        rerankProperties.setModelName("Qwen/Qwen3-Reranker-8B");
        embedding = new SiliconFlowEmbeddingClient(embeddingProperties, http);
        reranker = new SiliconFlowRerankClient(rerankProperties, http);
    }

    /** 释放夹具连接及工作线程。 */
    @AfterEach
    void stopServer() {
        responseGate.countDown();

        if (server != null) server.stop(0);

        if (executor != null) executor.shutdownNow();
    }

    @Test
    void defaultsRequireExplicitEnablementAndEgressConsent() {
        EmbeddingProperties defaults = new EmbeddingProperties();

        assertEquals(1024, defaults.getDimensions());
        assertEquals("", defaults.getModelName());
        assertEquals("", defaults.getBaseUrl());
        assertFalse(defaults.isEnabled());
        assertFalse(defaults.isAllowDocumentEgress());
        assertFalse(new SiliconFlowEmbeddingClient(defaults, http).enabled());

        RerankProperties rerankDefaults = new RerankProperties();

        assertEquals("", rerankDefaults.getModelName());
        assertEquals("", rerankDefaults.getBaseUrl());
        assertFalse(rerankDefaults.isEnabled());
        assertFalse(rerankDefaults.isAllowDocumentEgress());
        assertEquals(0, requests.get());
    }

    @Test
    void embeddingPostsExpectedContractAndReordersByIndex() throws IOException {
        response = embeddings(item(1, vector(2)), item(0, vector(1)));

        List<List<Float>> result = embedding.embed(List.of("上线前备份", "回滚操作"));

        assertEquals(1.0f, result.get(0).get(0).floatValue());
        assertEquals(2.0f, result.get(1).get(0).floatValue());
        assertEquals(64, result.get(0).size());
        assertEquals("/v1/embeddings", receivedPath);
        assertEquals("Bearer test-key", authorization);
        assertEquals("Qwen/Qwen3-Embedding-8B", received.get("model").asText());
        assertEquals("上线前备份", received.get("input").get(0).asText());
        assertEquals(64, received.get("dimensions").intValue());
        assertEquals("float", received.get("encoding_format").asText());
        assertEquals(1, requests.get());
    }

    @Test
    void embeddingRejectsMissingDuplicateAndOutOfRangeIndices() throws IOException {
        List<String> invalid = List.of(
                "{\"data\":[]}",
                embeddings(item(0, vector(1)), item(0, vector(2))),
                embeddings(item(0, vector(1)), item(2, vector(2))),
                embeddings(item(0, vector(1)), Map.of("embedding", vector(2))),
                embeddings(item(0, vector(1)), Map.of("index", "1", "embedding", vector(2))),
                embeddings(item(0, vector(1)), Map.of("index", Long.MAX_VALUE, "embedding", vector(2))));

        for (String invalidResponse : invalid) {
            response = invalidResponse;

            assertThrows(RetrievalException.class, () -> embedding.embed(List.of("甲", "乙")));
        }

        assertEquals(invalid.size(), requests.get());
    }

    @Test
    void embeddingRejectsWrongDimensionsNonFiniteValuesAndZeroVectors() throws IOException {
        List<Object> overflow = new ArrayList<>(Collections.nCopies(64, 0));
        overflow.set(0, 1e100);

        List<Object> notNumeric = new ArrayList<>(Collections.nCopies(64, 0));
        notNumeric.set(0, "NaN");

        for (List<?> values : List.of(List.of(1.0), overflow, notNumeric, vector(0))) {
            response = embeddings(item(0, values));

            assertThrows(RetrievalException.class, () -> embedding.embed(List.of("合成资料")));
        }

        assertEquals(4, requests.get());
    }

    @Test
    void embeddingRejectsInvalidInputsBeforeNetwork() {
        assertThrows(RetrievalException.class, () -> embedding.embed(null));
        assertThrows(RetrievalException.class, () -> embedding.embed(List.of()));
        assertThrows(RetrievalException.class, () -> embedding.embed(Collections.nCopies(17, "文本")));
        assertThrows(RetrievalException.class, () -> embedding.embed(Arrays.asList("文本", null)));
        assertThrows(RetrievalException.class, () -> embedding.embed(List.of(" ")));
        assertThrows(RetrievalException.class, () -> embedding.embed(List.of("字".repeat(16001))));

        embeddingProperties.setDimensions(33);

        assertThrows(RetrievalException.class, () -> embedding.embed(List.of("文本")));

        embeddingProperties.setDimensions(64);
        embeddingProperties.setModelName("");

        assertThrows(RetrievalException.class, () -> embedding.embed(List.of("文本")));
        assertEquals(0, requests.get());
    }

    @Test
    void embeddingNeverCallsWithoutConsentOrWithPlaceholderKeys() {
        embeddingProperties.setEnabled(false);

        assertFalse(embedding.enabled());
        assertThrows(RetrievalException.class, () -> embedding.embed(List.of("文本")));

        embeddingProperties.setEnabled(true);
        embeddingProperties.setAllowDocumentEgress(false);

        assertFalse(embedding.enabled());
        assertThrows(RetrievalException.class, () -> embedding.embed(List.of("文本")));

        embeddingProperties.setAllowDocumentEgress(true);

        for (String key : Arrays.asList(null, "", " ", "YOUR_API_KEY", "replace-me", "placeholder", "占位", "填写密钥", "请填写API Key")) {
            embeddingProperties.setApiKey(key);

            assertFalse(embedding.enabled());
            assertThrows(RetrievalException.class, () -> embedding.embed(List.of("文本")));
        }

        assertEquals(0, requests.get());
    }

    @Test
    void rerankerReturnsValidatedInputIndicesAndDoesNotTrustRemoteDocuments() {
        response = """
                {"results":[
                  {"index":1,"relevance_score":0.9,"document":{"text":"远端替换正文"}},
                  {"index":0,"relevance_score":0.5}]}
                """;

        assertEquals(List.of(1, 0), reranker.rerank("如何回滚", List.of("备份", "回滚", "发布"), 2));
        assertEquals("/v1/rerank", receivedPath);
        assertEquals("Bearer test-key", authorization);
        assertEquals("Qwen/Qwen3-Reranker-8B", received.get("model").asText());
        assertEquals("如何回滚", received.get("query").asText());
        assertEquals(3, received.get("documents").size());
        assertEquals(2, received.get("top_n").intValue());
        assertFalse(received.get("return_documents").booleanValue());
        assertTrue(received.get("instruction").isTextual());
        assertFalse(received.has("max_chunks_per_doc"));
        assertFalse(received.has("overlap_tokens"));
    }

    @Test
    void rerankerRejectsMalformedIndicesCountsAndScores() {
        List<String> invalid = List.of(
                "{\"results\":[]}",
                "{\"results\":[{\"index\":0,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.5}]}",
                "{\"results\":[{\"index\":0,\"relevance_score\":0.9},{\"index\":2,\"relevance_score\":0.5}]}",
                "{\"results\":[{\"index\":0,\"relevance_score\":0.9},{\"index\":\"1\",\"relevance_score\":0.5}]}",
                "{\"results\":[{\"index\":0,\"relevance_score\":0.9},{\"index\":1}]}",
                "{\"results\":[{\"index\":0,\"relevance_score\":0.9},{\"index\":1,\"relevance_score\":\"NaN\"}]}",
                "{\"results\":[{\"index\":0,\"relevance_score\":0.9},{\"index\":1,\"relevance_score\":1e999}]}");

        for (String invalidResponse : invalid) {
            response = invalidResponse;

            assertThrows(RetrievalException.class, () -> reranker.rerank("问题", List.of("甲", "乙"), 2));
        }

        assertEquals(invalid.size(), requests.get());
    }

    @Test
    void rerankerRejectsInvalidInputsBeforeNetwork() {
        assertThrows(RetrievalException.class, () -> reranker.rerank(null, List.of("文本"), 1));
        assertThrows(RetrievalException.class, () -> reranker.rerank(" ", List.of("文本"), 1));
        assertThrows(RetrievalException.class, () -> reranker.rerank("字".repeat(2001), List.of("文本"), 1));
        assertThrows(RetrievalException.class, () -> reranker.rerank("问题", null, 1));
        assertThrows(RetrievalException.class, () -> reranker.rerank("问题", List.of(), 1));
        assertThrows(RetrievalException.class, () -> reranker.rerank("问题", Collections.nCopies(21, "文本"), 1));
        assertThrows(RetrievalException.class, () -> reranker.rerank("问题", Arrays.asList("文本", null), 1));
        assertThrows(RetrievalException.class, () -> reranker.rerank("问题", List.of("字".repeat(2001)), 1));
        assertThrows(RetrievalException.class, () -> reranker.rerank("问题", List.of("文本"), 0));
        assertThrows(RetrievalException.class, () -> reranker.rerank("问题", List.of("文本"), 2));

        rerankProperties.setModelName("");

        assertThrows(RetrievalException.class, () -> reranker.rerank("问题", List.of("文本"), 1));
        assertEquals(0, requests.get());
    }

    @Test
    void rerankerNeverCallsWithoutConsentOrValidKey() {
        rerankProperties.setEnabled(false);

        assertFalse(reranker.enabled());
        assertThrows(RetrievalException.class, () -> reranker.rerank("问题", List.of("文本"), 1));

        rerankProperties.setEnabled(true);
        rerankProperties.setAllowDocumentEgress(false);

        assertFalse(reranker.enabled());
        assertThrows(RetrievalException.class, () -> reranker.rerank("问题", List.of("文本"), 1));

        rerankProperties.setAllowDocumentEgress(true);
        rerankProperties.setApiKey("YOUR_API_KEY");

        assertFalse(reranker.enabled());
        assertThrows(RetrievalException.class, () -> reranker.rerank("问题", List.of("文本"), 1));
        assertEquals(0, requests.get());
    }

    @Test
    void httpErrorsDoNotRetryOrExposeResponseBodies() {
        response = "test-key 私有正文及供应商内部信息";

        for (int code : List.of(401, 429, 503)) {
            status = code;

            int before = requests.get();
            RetrievalException error = assertThrows(RetrievalException.class,
                    () -> embedding.embed(List.of("合成资料")));

            assertTrue(error.getMessage().contains(Integer.toString(code)));
            assertFalse(error.getMessage().contains("test-key"));
            assertFalse(error.getMessage().contains("私有正文"));
            assertNull(error.getCause());
            assertEquals(before + 1, requests.get());
        }
    }

    @Test
    void totalCallTimeoutIsBoundedAndNotRetried() {
        embeddingProperties.setTimeoutSeconds(1);
        responseGate = new CountDownLatch(1);

        assertTimeoutPreemptively(Duration.ofSeconds(4), () ->
                assertThrows(RetrievalException.class, () -> embedding.embed(List.of("合成资料"))));
        assertEquals(1, requests.get());
    }

    @Test
    void redirectsAreRejectedWithoutForwardingCredentials() {
        status = 307;

        assertThrows(RetrievalException.class, () -> embedding.embed(List.of("合成资料")));
        assertEquals(1, requests.get());
        assertEquals("/v1/embeddings", receivedPath);
    }

    @Test
    void chunkedResponseCannotBypassSizeLimit() {
        chunked = true;
        response = "{\"padding\":\"" + "x".repeat(4 * 1024 * 1024) + "\"}";

        assertThrows(RetrievalException.class, () -> embedding.embed(List.of("合成资料")));
        assertEquals(1, requests.get());
    }

    @Test
    void httpRejectsDuplicateKeysTrailingJsonAndNonObjectBodies() {
        for (String invalid : List.of("{\"data\":[],\"data\":[]}", "{} {}", "[]", "")) {
            response = invalid;

            assertThrows(RetrievalException.class, () -> http.post(baseUrl(), "/test", "", Map.of(), 3));
        }

        assertEquals(4, requests.get());
    }

    @Test
    void sharedTransportSupportsTokenlessMilvusRequests() {
        response = "{\"code\":0,\"data\":{}}";

        JsonNode result = http.post(baseUrl(), "/vectordb/test", "", Map.of("collectionName", "test"), 3);

        assertEquals(0, result.get("code").intValue());
        assertNull(authorization);
        assertEquals("test", received.get("collectionName").asText());
    }

    @Test
    void invalidConnectionSettingsAndOversizedBodiesNeverReachNetwork() {
        assertThrows(RetrievalException.class, () -> http.post("", "/test", "", Map.of(), 3));
        assertThrows(RetrievalException.class, () -> http.post(baseUrl(), "test", "", Map.of(), 3));
        assertThrows(RetrievalException.class, () -> http.post(baseUrl(), "/test", "", Map.of(), 0));
        assertThrows(RetrievalException.class, () -> http.post(baseUrl(), "/test", "", Map.of(), 61));
        assertThrows(RetrievalException.class, () -> http.post(baseUrl(), "/test", "",
                Map.of("text", "x".repeat(4 * 1024 * 1024)), 3));
        assertEquals(0, requests.get());
    }

    /** @return 当前夹具的接口基础地址 */
    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /**
     * 创建固定维度的测试向量。
     * @param first 首个元素
     * @return 测试向量
     */
    private List<Float> vector(float first) {
        List<Float> vector = new ArrayList<>(Collections.nCopies(embeddingProperties.getDimensions(), 0.0f));
        vector.set(0, first);

        return vector;
    }

    /**
     * 创建单条向量响应。
     * @param index 原输入索引
     * @param vector 测试向量
     * @return 响应条目
     */
    private Map<String, Object> item(int index, List<?> vector) {
        return Map.of("index", index, "embedding", vector);
    }

    /**
     * 序列化向量响应。
     * @param items 响应条目
     * @return JSON 文本
     */
    private String embeddings(Object... items) throws IOException {
        return mapper.writeValueAsString(Map.of("data", Arrays.asList(items)));
    }

    /**
     * 记录请求并按测试设置响应。
     * @param exchange 本地 HTTP 交换
     */
    private void respond(HttpExchange exchange) throws IOException {
        try {
            requests.incrementAndGet();
            receivedPath = exchange.getRequestURI().getPath();
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            received = mapper.readTree(exchange.getRequestBody());

            if (!responseGate.await(5, TimeUnit.SECONDS)) return;

            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "application/json");

            if (status >= 300 && status < 400) {
                exchange.getResponseHeaders().set("Location", baseUrl() + "/must-not-follow");
            }

            exchange.sendResponseHeaders(status, chunked || bytes.length == 0 ? 0 : bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // 客户端超时或达到响应上限后主动断开属于测试预期。
        } finally {
            exchange.close();
        }
    }
}
