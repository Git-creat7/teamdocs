package asia.creat.teamdocsbackend.retrieval;

import asia.creat.config.EmbeddingProperties;
import asia.creat.config.MilvusProperties;
import asia.creat.retrieval.MilvusVectorClient;
import asia.creat.retrieval.RetrievalException;
import asia.creat.retrieval.RetrievalHttp;
import asia.creat.vo.ChunkHitVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MilvusVectorClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Queue<String> responses = new ConcurrentLinkedQueue<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private MilvusProperties properties;
    private EmbeddingProperties embedding;
    private MilvusVectorClient client;

    /** 只启动回环 HTTP 夹具，不连接真实 Milvus。 */
    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonNode body = json.readTree(exchange.getRequestBody());

            requests.add(new Request(exchange.getRequestURI().getPath(), body,
                    exchange.getRequestHeaders().getFirst("Authorization")));

            String response = responses.poll();

            if (response == null) response = "{\"code\":999,\"message\":\"unexpected request\"}";

            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);

            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        properties = new MilvusProperties();
        properties.setEnabled(true);
        properties.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setToken("local-test-token");
        embedding = new EmbeddingProperties();
        embedding.setDimensions(2);
        embedding.setModelName("Qwen/Qwen3-Embedding-8B");
        client = new MilvusVectorClient(properties, embedding, new RetrievalHttp(json));
    }

    /** 每个用例结束后关闭本地夹具。 */
    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    /** 关闭状态不发生网络调用。 */
    @Test
    void disabledClientDoesNotCallServer() {
        properties.setEnabled(false);

        assertFalse(client.enabled());
        assertThrows(RetrievalException.class, client::ensureCollection);
        assertTrue(requests.isEmpty());
    }

    /** 检索提前按空间和文档过滤，并接受 REST 字符串 Int64 标识。 */
    @Test
    void searchFiltersBeforeLimitAndReturnsOnlyCandidateMetadata() {
        enqueue(description(true));

        ObjectNode candidate = candidate();
        candidate.put("chunk_id", "9007199254740993");
        candidate.put("id", "9007199254740993");
        enqueue(response(List.of(candidate)));

        var hits = client.search(1L, 2L, vector(), 6);

        assertEquals(1, hits.size());
        assertEquals(9007199254740993L, hits.get(0).getChunkId());
        assertEquals(2L, hits.get(0).getDocumentId());
        assertEquals(3, hits.get(0).getParseVersion());

        Request search = requests.get(1);

        assertEquals("/v2/vectordb/entities/search", search.path());
        assertEquals("space_id == 1 and document_id == 2", search.body().path("filter").asText());
        assertEquals(6, search.body().path("limit").asInt());
        assertEquals("Strong", search.body().path("consistencyLevel").asText());
        assertEquals("embedding", search.body().path("annsField").asText());
        assertEquals("Bearer local-test-token", search.authorization());
        assertEquals(json.valueToTree(List.of("chunk_id", "document_id", "space_id", "parse_version")),
                search.body().get("outputFields"));
        assertFalse(search.body().toString().contains("excerpt"));
    }

    /** 空间内搜索不添加文档过滤，向量集合不存在时不会自动创建。 */
    @Test
    void missingCollectionIsNotCreatedBySearch() {
        enqueue(json.valueToTree(Map.of("code", 100)));

        assertThrows(RetrievalException.class, () -> client.search(1L, null, vector(), 20));
        assertEquals(1, requests.size());
        assertEquals("/v2/vectordb/collections/describe", requests.get(0).path());
    }

    /** 已存在集合只检查并加载，不删除或重建数据。 */
    @Test
    void compatibleCollectionIsLoadedWithoutRecreation() {
        enqueue(description(true));
        enqueue(response(Map.of()));
        client.ensureCollection();

        assertEquals(List.of("/v2/vectordb/collections/describe", "/v2/vectordb/collections/load"),
                requests.stream().map(Request::path).toList());
    }

    /** 只有明确不存在才创建五字段集合，再创建 COSINE FLAT 索引并加载。 */
    @Test
    void createUsesExplicitSchemaAndSeparateIndexThenLoad() {
        enqueue(json.valueToTree(Map.of("code", 100)));
        enqueue(response(Map.of()));
        enqueue(description(false));
        enqueue(response(Map.of()));
        enqueue(response(Map.of()));

        client.ensureCollection();

        assertEquals(5, requests.size());

        JsonNode create = requests.get(1).body();

        assertEquals("/v2/vectordb/collections/create", requests.get(1).path());
        assertEquals("teamdocs-vector-v1|model=Qwen/Qwen3-Embedding-8B|dimensions=2",
                create.path("description").asText());
        assertFalse(create.path("schema").path("autoId").asBoolean(true));
        assertFalse(create.path("schema").path("enableDynamicField").asBoolean(true));

        JsonNode fields = create.path("schema").path("fields");

        assertEquals(5, fields.size());
        assertEquals("chunk_id", fields.get(0).path("fieldName").asText());
        assertTrue(fields.get(0).path("isPrimary").asBoolean());
        assertEquals("2", fields.get(4).path("elementTypeParams").path("dim").asText());

        JsonNode index = requests.get(3).body().path("indexParams").get(0);

        assertEquals("/v2/vectordb/indexes/create", requests.get(3).path());
        assertEquals("FLAT", index.path("indexType").asText());
        assertEquals("COSINE", index.path("metricType").asText());
        assertEquals("/v2/vectordb/collections/load", requests.get(4).path());
    }

    /** 创建竞争只在明确的已存在错误后复查，不清理其他进程创建的集合。 */
    @Test
    void creationRaceRechecksExistingSchemaOnce() {
        enqueue(json.valueToTree(Map.of("code", 100)));
        enqueue(json.valueToTree(Map.of("code", 1100, "message", "collection already exists")));
        enqueue(description(true));
        enqueue(response(Map.of()));
        client.ensureCollection();

        assertEquals(4, requests.size());
        assertFalse(requests.stream().anyMatch(request -> request.path().contains("drop")));
    }

    /** 权限错误既不触发建表，也不泄露远端错误中的正文或凭据。 */
    @Test
    void protocolPermissionErrorIsNotTreatedAsMissingCollection() {
        enqueue(json.valueToTree(Map.of("code", 1800, "message", "secret-document-token")));

        var error = assertThrows(RetrievalException.class, client::ensureCollection);

        assertEquals(1, requests.size());
        assertFalse(error.getMessage().contains("secret-document-token"));
        assertTrue(error.getMessage().contains("1800"));
    }

    /** 签名、维度、字段类型或动态字段不兼容时拒绝写入。 */
    @Test
    void incompatibleSchemaIsNeverModified() {
        ObjectNode wrongModel = description(true);

        ((ObjectNode) wrongModel.get("data")).put("description", "another-model");

        ObjectNode wrongDimension = description(true);

        ((ObjectNode) wrongDimension.path("data").path("fields").get(4).path("params").get(0)).put("value", "3");

        ObjectNode wrongType = description(true);

        ((ObjectNode) wrongType.path("data").path("fields").get(1)).put("type", "VarChar");

        ObjectNode dynamic = description(true);

        ((ObjectNode) dynamic.get("data")).put("enableDynamicField", true);

        for (ObjectNode invalid : List.of(wrongModel, wrongDimension, wrongType, dynamic)) {
            enqueue(invalid);

            assertThrows(RetrievalException.class, client::ensureCollection);
        }

        assertEquals(4, requests.size());
        assertTrue(requests.stream().allMatch(request -> request.path().endsWith("/describe")));
    }

    /** 不兼容的相似度索引不被静默替换。 */
    @Test
    void incompatibleIndexIsRejected() {
        ObjectNode wrongMetric = description(true);

        ((ObjectNode) wrongMetric.path("data").path("indexes").get(0)).put("metricType", "L2");
        enqueue(wrongMetric);

        assertThrows(RetrievalException.class, client::ensureCollection);
        assertEquals(1, requests.size());
    }

    /** 写入仅包含标识和向量，且检查服务端确认的数量。 */
    @Test
    void upsertDoesNotCopyPrivateText() {
        enqueue(description(true));
        enqueue(response(Map.of("upsertCount", 1)));
        client.upsert(List.of(chunk()), List.of(vector()));

        Request request = requests.get(1);

        assertEquals("/v2/vectordb/entities/upsert", request.path());

        JsonNode row = request.body().path("data").get(0);

        assertEquals(5, row.size());
        assertEquals(10, row.path("chunk_id").asLong());
        assertEquals(2, row.path("embedding").size());
        assertEquals(0.6, row.path("embedding").get(0).asDouble(), 0.000001);
        assertEquals(0.8, row.path("embedding").get(1).asDouble(), 0.000001);
        assertFalse(request.body().toString().contains("private-document-content"));
        assertFalse(request.body().toString().contains("private-document-name"));
    }

    /** 不接受部分写入或缺少写入数量的成功响应。 */
    @Test
    void upsertRejectsInvalidAcknowledgementAndDuplicateKeys() {
        enqueue(description(true));
        enqueue(response(Map.of("upsertCount", 0)));

        assertThrows(RetrievalException.class, () -> client.upsert(List.of(chunk()), List.of(vector())));
        assertThrows(RetrievalException.class,
                () -> client.upsert(List.of(chunk(), chunk()), List.of(vector(), vector())));
        assertEquals(2, requests.size());
    }

    /** 清理只接受可信文档 ID，并将集合不存在视为幂等成功。 */
    @Test
    void deleteUsesDocumentFilterWithoutCreatingCollection() {
        enqueue(description(true));
        enqueue(response(Map.of("deleteCount", 1)));
        client.deleteDocument(2L);

        assertEquals("document_id == 2", requests.get(1).body().path("filter").asText());
        assertEquals("/v2/vectordb/entities/delete", requests.get(1).path());
        enqueue(json.valueToTree(Map.of("code", 100)));
        client.deleteDocument(2L);

        assertEquals(3, requests.size());
        assertThrows(RetrievalException.class, () -> client.deleteDocument(-1L));
        assertEquals(3, requests.size());
    }

    /** 伪造空间、文档、重复主键及非整型版本都使整个候选响应无效。 */
    @Test
    void forgedCandidatesAreRejected() {
        ObjectNode otherSpace = candidate();
        otherSpace.put("space_id", 99);

        ObjectNode otherDocument = candidate();
        otherDocument.put("document_id", 99);

        ObjectNode fractionalVersion = candidate();
        fractionalVersion.put("parse_version", 3.5);

        ObjectNode inconsistentId = candidate();
        inconsistentId.put("id", 999);

        for (ObjectNode invalid : List.of(otherSpace, otherDocument, fractionalVersion, inconsistentId)) {
            enqueue(description(true));
            enqueue(response(List.of(invalid)));

            assertThrows(RetrievalException.class, () -> client.search(1L, 2L, vector(), 6));
        }

        enqueue(description(true));
        enqueue(response(List.of(candidate(), candidate())));

        assertThrows(RetrievalException.class, () -> client.search(1L, 2L, vector(), 6));
    }

    /** 错误维度、零向量、非有限值和越界候选数量在请求前被拒绝。 */
    @Test
    void invalidInputsDoNotReachServer() {
        assertThrows(RetrievalException.class, () -> client.search(1L, null, List.of(1f), 6));
        assertThrows(RetrievalException.class, () -> client.search(1L, null, List.of(0f, 0f), 6));
        assertThrows(RetrievalException.class, () -> client.search(1L, null, List.of(Float.NaN, 1f), 6));
        assertThrows(RetrievalException.class, () -> client.search(1L, null, List.of(Float.POSITIVE_INFINITY, 1f), 6));
        assertThrows(RetrievalException.class, () -> client.search(1L, null, vector(), 21));

        properties.setCollection("other_collection; drop");

        assertThrows(RetrievalException.class, client::ensureCollection);
        assertTrue(requests.isEmpty());
    }

    /** 首次解析版本从零开始，必须支持写入和返回，而不能当作非法 ID。 */
    @Test
    void initialParseVersionZeroIsValid() {
        ChunkHitVO firstVersion = chunk();
        firstVersion.setParseVersion(0);
        enqueue(description(true));
        enqueue(response(Map.of("upsertCount", 1)));
        client.upsert(List.of(firstVersion), List.of(vector()));

        ObjectNode zeroVersion = candidate();
        zeroVersion.put("parse_version", 0);
        enqueue(description(true));
        enqueue(response(List.of(zeroVersion)));

        assertEquals(0, client.search(1L, 2L, vector(), 6).get(0).getParseVersion());
    }

    /** 缺失 code 不能因默认数值为零而被视作成功。 */
    @Test
    void missingStatusCodeIsRejected() {
        enqueue(json.valueToTree(Map.of("data", Map.of())));

        assertThrows(RetrievalException.class, client::ensureCollection);
        assertEquals(1, requests.size());
    }

    /** 按官方描述响应格式构造集合，维度位于 params 键值对数组。 */
    private ObjectNode description(boolean indexed) {
        List<Map<String, Object>> fields = new ArrayList<>();

        for (String name : List.of("chunk_id", "document_id", "space_id", "parse_version", "embedding")) {
            String type = "embedding".equals(name) ? "FloatVector" : "parse_version".equals(name) ? "Int32" : "Int64";

            fields.add(Map.of("name", name, "type", type, "primaryKey", "chunk_id".equals(name),
                    "autoId", false, "nullable", false,
                    "params", "embedding".equals(name) ? List.of(Map.of("key", "dim", "value", "2")) : List.of()));
        }

        return response(Map.of("collectionName", properties.getCollection(),
                "description", "teamdocs-vector-v1|model=" + embedding.getModelName() + "|dimensions=2",
                "autoId", false, "enableDynamicField", false, "fields", fields,
                "indexes", indexed ? List.of(Map.of("indexName", "embedding_cosine", "fieldName", "embedding", "metricType", "COSINE")) : List.of()));
    }

    /** 构造不包含正文的合法检索候选。 */
    private ObjectNode candidate() {
        return json.valueToTree(Map.of("chunk_id", 10L, "id", 10L, "document_id", 2L,
                "space_id", 1L, "parse_version", 3, "distance", 0.9));
    }

    /** 构造业务分块，便于检查正文没有外发给 Milvus。 */
    private ChunkHitVO chunk() {
        ChunkHitVO chunk = new ChunkHitVO();
        chunk.setChunkId(10L);
        chunk.setDocumentId(2L);
        chunk.setSpaceId(1L);
        chunk.setParseVersion(3);
        chunk.setExcerpt("private-document-content");
        chunk.setDocumentName("private-document-name");

        return chunk;
    }

    /** 返回固定的非零测试向量。 */
    private List<Float> vector() {
        return List.of(0.6f, 0.8f);
    }

    /** 包装 REST 成功响应，不执行任何真实模型调用。 */
    private ObjectNode response(Object data) {
        return json.valueToTree(Map.of("code", 0, "data", data));
    }

    /** 将下一条夹具响应放入本地队列。 */
    private void enqueue(JsonNode response) {
        responses.add(response.toString());
    }

    private record Request(String path, JsonNode body, String authorization) { }
}
