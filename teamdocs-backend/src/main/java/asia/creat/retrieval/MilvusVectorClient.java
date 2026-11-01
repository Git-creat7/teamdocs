package asia.creat.retrieval;

import asia.creat.config.EmbeddingProperties;
import asia.creat.config.MilvusProperties;
import asia.creat.vo.ChunkHitVO;
import asia.creat.vo.ChunkIndexHit;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class MilvusVectorClient {
    private static final String PREFIX = "/v2/vectordb/";
    private static final String INDEX_NAME = "embedding_cosine";
    private static final Map<String, String> FIELD_TYPES = Map.of(
            "chunk_id", "Int64", "document_id", "Int64", "space_id", "Int64",
            "parse_version", "Int32", "embedding", "FloatVector");

    private final MilvusProperties properties;
    private final EmbeddingProperties embeddingProperties;
    private final RetrievalHttp http;

    /** 返回向量索引开关，不通过网络探测服务状态。 */
    public boolean enabled() {
        return properties.isEnabled();
    }

    /** 返回默认基础集合名称并校验合法性。 */
    public String defaultCollection() {
        return validateCollectionName(properties.getCollection());
    }

    /**
     * 生成指定文档与代次的蓝绿隔离集合名称，防止同一 chunk_id 原地覆盖旧代次向量。
     * @param documentId 文档ID
     * @param generation 任务代次
     * @return 独立集合名称
     */
    public String collectionName(Long documentId, long generation) {
        requirePositive(documentId);
        if (generation < 1) {
            throw failure("任务代次无效");
        }
        return validateCollectionName(defaultCollection() + "_d" + documentId + "_g" + generation);
    }

    /** 只读核对现有集合，不创建集合或写入测试向量。 */
    public void checkHealth() {
        checkHealth(properties.getCollection());
    }

    /** 只读核对指定集合结构与模型签名。 */
    public void checkHealth(String collection) {
        validateSchema(collection, success(post(collection, "collections/describe", Map.of())), false);
    }

    /**
     * 切换前对新集合执行完整校验：核对字段、维度、索引结构，并执行抽样查询验证可读性。
     * @param collection 待切换的新集合名称
     * @param spaceId 所属空间ID
     * @param documentId 文档ID
     * @param sampleVector 抽样查询向量
     */
    public void verifyCollection(String collection, Long spaceId, Long documentId, List<Float> sampleVector) {
        requirePositive(spaceId);
        requirePositive(documentId);
        validateVector(sampleVector);

        JsonNode data = success(post(collection, "collections/describe", Map.of()));
        validateSchema(collection, data, true);

        List<ChunkIndexHit> sampleHits = search(collection, spaceId, documentId, sampleVector, 1);
        if (sampleHits == null || sampleHits.isEmpty()) {
            throw failure("新集合抽样查询验证未命中");
        }
    }

    /** 在指定空间和可选文档内召回候选；正文与权限仍由 MySQL 复核。 */
    public List<ChunkIndexHit> search(Long spaceId, Long documentId, List<Float> vector, int limit) {
        return search(properties.getCollection(), spaceId, documentId, vector, limit);
    }

    /** 在指定集合、空间和可选文档内召回候选；正文与权限仍由 MySQL 复核。 */
    public List<ChunkIndexHit> search(String collection, Long spaceId, Long documentId, List<Float> vector, int limit) {
        requirePositive(spaceId);

        if (documentId != null) requirePositive(documentId);

        validateVector(vector);

        if (limit < 1 || limit > 20) throw failure("候选数量越界");

        validateSchema(collection, success(post(collection, "collections/describe", Map.of())), false);

        String filter = "space_id == " + spaceId;

        if (documentId != null) filter += " and document_id == " + documentId;

        Map<String, Object> searchParamsInner = new LinkedHashMap<>();
        if ("HNSW".equalsIgnoreCase(properties.getIndexType())) {
            searchParamsInner.put("ef", properties.getHnswEf());
        }

        JsonNode data = success(post(collection, "entities/search", Map.of(
                "data", List.of(vector), "annsField", "embedding", "filter", filter,
                "limit", limit, "consistencyLevel", "Strong",
                "outputFields", List.of("chunk_id", "document_id", "space_id", "parse_version"),
                "searchParams", Map.of("metricType", "COSINE", "params", searchParamsInner))));

        if (!data.isArray() || data.size() > limit) throw failure("候选响应无效");

        List<ChunkIndexHit> hits = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        for (JsonNode row : data) {
            long chunkId = positiveLong(row.get("chunk_id"));
            long resultDocumentId = positiveLong(row.get("document_id"));
            long resultSpaceId = positiveLong(row.get("space_id"));
            int version = parseVersion(row.get("parse_version"));
            JsonNode distance = row.get("distance");

            if (resultSpaceId != spaceId || (documentId != null && resultDocumentId != documentId)
                    || !seen.add(chunkId)
                    || (row.has("id") && positiveLong(row.get("id")) != chunkId)
                    || distance == null || !distance.isNumber() || !Double.isFinite(distance.doubleValue())) {
                throw failure("候选响应无效");
            }

            hits.add(new ChunkIndexHit(chunkId, resultDocumentId, version, null));
        }

        return hits;
    }

    /** 批量写入最多 16 个分块的标识与向量，不向 Milvus 复制正文。 */
    public void upsert(List<ChunkHitVO> chunks, List<List<Float>> vectors) {
        upsert(properties.getCollection(), chunks, vectors);
    }

    /** 向指定集合批量写入最多 16 个分块的标识与向量，不向 Milvus 复制正文。 */
    public void upsert(String collection, List<ChunkHitVO> chunks, List<List<Float>> vectors) {
        if (chunks == null || chunks.isEmpty() || chunks.size() > 16
                || vectors == null || chunks.size() != vectors.size()) {
            throw failure("写入批次无效");
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        for (int i = 0; i < chunks.size(); i++) {
            ChunkHitVO chunk = chunks.get(i);

            if (chunk == null) throw failure("分块标识无效");

            requirePositive(chunk.getChunkId());
            requirePositive(chunk.getDocumentId());
            requirePositive(chunk.getSpaceId());

            if (chunk.getParseVersion() == null || chunk.getParseVersion() < 0 || !seen.add(chunk.getChunkId())) {
                throw failure("分块标识无效");
            }

            validateVector(vectors.get(i));
            rows.add(Map.of("chunk_id", chunk.getChunkId(), "document_id", chunk.getDocumentId(),
                    "space_id", chunk.getSpaceId(), "parse_version", chunk.getParseVersion(),
                    "embedding", vectors.get(i)));
        }

        validateSchema(collection, success(post(collection, "collections/describe", Map.of())), false);

        JsonNode data = success(post(collection, "entities/upsert", Map.of("data", rows)));

        if (positiveLong(data.get("upsertCount")) != chunks.size()) throw failure("向量写入数量不一致");
    }

    /** 清理指定文档的向量；集合不存在时视为已经清理，不创建集合。 */
    public void deleteDocument(Long documentId) {
        deleteDocument(properties.getCollection(), documentId);
    }

    /** 在指定集合内清理指定文档的向量；集合不存在时视为已经清理，不创建集合。 */
    public void deleteDocument(String collection, Long documentId) {
        requirePositive(documentId);

        JsonNode description = post(collection, "collections/describe", Map.of());

        if (code(description) == 100) return;

        validateSchema(collection, success(description), false);
        success(post(collection, "entities/delete", Map.of("filter", "document_id == " + documentId)));
    }

    /** 清理指定文档中非目标版本的旧向量；集合不存在时视为已经清理，不创建集合。 */
    public void deleteStaleChunks(Long documentId, Integer targetParseVersion) {
        deleteStaleChunks(properties.getCollection(), documentId, targetParseVersion);
    }

    /** 在指定集合内清理指定文档中非目标版本的旧向量；集合不存在时视为已经清理，不创建集合。 */
    public void deleteStaleChunks(String collection, Long documentId, Integer targetParseVersion) {
        requirePositive(documentId);
        if (targetParseVersion == null || targetParseVersion < 0) {
            throw failure("目标解析版本无效");
        }

        JsonNode description = post(collection, "collections/describe", Map.of());

        if (code(description) == 100) return;

        validateSchema(collection, success(description), false);
        String filter = "document_id == " + documentId + " and parse_version != " + targetParseVersion;
        success(post(collection, "entities/delete", Map.of("filter", filter)));
    }

    /** 删除已退役的旧代次独立集合；集合不存在时视为幂等成功。 */
    public synchronized void dropCollection(String collection) {
        validateCollectionName(collection);

        JsonNode description = post(collection, "collections/describe", Map.of());

        if (code(description) == 100) {
            return;
        }

        validateSchema(collection, success(description), false);
        success(post(collection, "collections/drop", Map.of()));
    }

    /** 建立或核对专用集合及索引，拒绝修改不兼容的已有集合。 */
    public synchronized void ensureCollection() {
        ensureCollection(properties.getCollection());
    }

    /** 建立或核对指定名称的专用集合及索引，拒绝修改不兼容的已有集合。 */
    public synchronized void ensureCollection(String collection) {
        validateCollectionName(collection);
        JsonNode description = post(collection, "collections/describe", Map.of());

        if (code(description) == 100) {
            JsonNode created = post(collection, "collections/create", Map.of(
                    "description", signature(), "schema", schema(),
                    "params", Map.of("consistencyLevel", "Strong")));

            if (code(created) != 0) {
                String message = created.path("message").asText("").toLowerCase(Locale.ROOT);

                if (code(created) != 1100 || !message.contains("already exist")) success(created);
            }

            description = post(collection, "collections/describe", Map.of());
        }

        JsonNode data = success(description);

        validateSchema(collection, data, false);

        JsonNode indexes = data.get("indexes");

        if (indexes == null || !indexes.isArray()) throw failure("集合索引描述无效");

        boolean indexed = false;

        for (JsonNode index : indexes) {
            if ("embedding".equals(index.path("fieldName").asText())) {
                if (!INDEX_NAME.equals(index.path("indexName").asText())
                        || !"COSINE".equals(index.path("metricType").asText())) {
                    throw failure("集合索引不兼容");
                }

                indexed = true;
            }
        }

        if (!indexed) {
            Map<String, Object> indexParams;
            if ("HNSW".equalsIgnoreCase(properties.getIndexType())) {
                indexParams = Map.of(
                        "fieldName", "embedding", "indexName", INDEX_NAME,
                        "metricType", "COSINE", "indexType", "HNSW",
                        "params", Map.of("M", properties.getHnswM(), "efConstruction", properties.getHnswEfConstruction()));
            } else {
                indexParams = Map.of(
                        "fieldName", "embedding", "indexName", INDEX_NAME,
                        "metricType", "COSINE", "indexType", "FLAT", "params", Map.of());
            }
            success(post(collection, "indexes/create", Map.of("indexParams", List.of(indexParams))));
        }

        success(post(collection, "collections/load", Map.of()));
    }

    /** 生成固定字段结构，关闭动态字段和自动主键。 */
    private Map<String, Object> schema() {
        List<Map<String, Object>> fields = new ArrayList<>();

        for (String name : List.of("chunk_id", "document_id", "space_id", "parse_version", "embedding")) {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("fieldName", name);
            field.put("dataType", FIELD_TYPES.get(name));
            field.put("isPrimary", "chunk_id".equals(name));

            if ("embedding".equals(name)) {
                field.put("elementTypeParams", Map.of("dim", String.valueOf(embeddingProperties.getDimensions())));
            }

            fields.add(field);
        }

        return Map.of("autoId", false, "enableDynamicField", false, "fields", fields);
    }

    /** 核对模型签名、维度及字段类型，避免混用向量空间或误写其他集合。 */
    private void validateSchema(String expectedCollection, JsonNode data, boolean requireIndex) {
        String validCollection = validateCollectionName(expectedCollection);
        if (!data.isObject() || !validCollection.equals(data.path("collectionName").asText())
                || !signature().equals(data.path("description").asText())
                || !isFalse(data.get("autoId")) || !isFalse(data.get("enableDynamicField"))) {
            throw failure("集合结构或模型签名不兼容");
        }

        JsonNode fields = data.get("fields");

        if (fields == null || !fields.isArray() || fields.size() != FIELD_TYPES.size()) {
            throw failure("集合字段不兼容");
        }

        Set<String> names = new HashSet<>();

        for (JsonNode field : fields) {
            String name = field.path("name").asText();

            if (!names.add(name) || !FIELD_TYPES.containsKey(name)
                    || !FIELD_TYPES.get(name).equals(field.path("type").asText())
                    || !isFalse(field.get("autoId")) || !isFalse(field.get("nullable"))
                    || !field.path("primaryKey").isBoolean()
                    || field.path("primaryKey").booleanValue() != "chunk_id".equals(name)) {
                throw failure("集合字段不兼容");
            }

            if ("embedding".equals(name)) {
                JsonNode params = field.get("params");

                if (params == null || !params.isArray()) throw failure("集合向量维度不兼容");

                int dimensionCount = 0;

                for (JsonNode param : params) {
                    if ("dim".equals(param.path("key").asText())) {
                        dimensionCount++;

                        if (positiveLong(param.get("value")) != embeddingProperties.getDimensions()) {
                            throw failure("集合向量维度不兼容");
                        }
                    }
                }

                if (dimensionCount != 1) throw failure("集合向量维度不兼容");
            }
        }

        if (requireIndex) {
            JsonNode indexes = data.get("indexes");
            if (indexes == null || !indexes.isArray()) {
                throw failure("集合索引描述无效");
            }
            boolean indexed = false;
            for (JsonNode index : indexes) {
                if ("embedding".equals(index.path("fieldName").asText())) {
                    if (!INDEX_NAME.equals(index.path("indexName").asText())
                            || !"COSINE".equals(index.path("metricType").asText())) {
                        throw failure("集合索引不兼容");
                    }
                    indexed = true;
                }
            }
            if (!indexed) {
                throw failure("集合缺少有效向量索引");
            }
        }
    }

    /** 为模型与维度生成稳定签名，变更后需使用新的专用集合。 */
    private String signature() {
        String model = embeddingProperties.getModelName();
        int dimensions = embeddingProperties.getDimensions();

        if (model == null || model.isBlank() || model.length() > 200 || dimensions < 1 || dimensions > 4096) {
            throw failure("向量模型配置无效");
        }

        return "teamdocs-vector-v1|model=" + model + "|dimensions=" + dimensions;
    }

    /** 检查向量维度、有限数值与非零范数。 */
    private void validateVector(List<Float> vector) {
        signature();

        if (vector == null || vector.size() != embeddingProperties.getDimensions()) {
            throw failure("向量维度无效");
        }

        double norm = 0;

        for (Float value : vector) {
            if (value == null || !Float.isFinite(value)) throw failure("向量数值无效");

            norm += (double) value * value;
        }

        if (norm == 0) throw failure("零向量不可检索");
    }

    /** 校验集合名称满足 Milvus 命名规范。 */
    private String validateCollectionName(String collection) {
        if (collection == null || !collection.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
            throw failure("集合名称无效");
        }
        return collection;
    }

    /** 发送固定 REST 操作，集合名不接受表达式或路径字符。 */
    private JsonNode post(String collection, String operation, Map<String, ?> parameters) {
        if (!enabled()) throw failure("向量索引未启用");

        String validCollection = validateCollectionName(collection);

        signature();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("collectionName", validCollection);
        request.putAll(parameters);

        return http.post(properties.getUrl(), PREFIX + operation, properties.getToken(), request,
                properties.getConnectTimeoutMillis(), properties.getReadTimeoutMillis(), properties.getWriteTimeoutMillis());
    }

    /** 成功响应取 data，错误仅暴露数字状态码，不转发服务端原文。 */
    private JsonNode success(JsonNode response) {
        int status = code(response);

        if (status != 0) throw failure("服务返回错误码 " + status);

        return response.path("data");
    }

    /** 要求 REST 响应明确包含整型 code，不把缺失字段当作成功。 */
    private int code(JsonNode response) {
        JsonNode status = response == null ? null : response.get("code");

        if (status == null || !status.isIntegralNumber() || !status.canConvertToInt()) {
            throw failure("服务响应格式无效");
        }

        return status.intValue();
    }

    /** REST 的 Int64 可能使用字符串表示；仅接受正整型或十进制整数字符串。 */
    private long positiveLong(JsonNode node) {
        if (node != null && node.isIntegralNumber() && node.canConvertToLong() && node.longValue() > 0) {
            return node.longValue();
        }

        if (node != null && node.isTextual() && node.textValue().matches("[1-9][0-9]{0,18}")) {
            try {
                return Long.parseLong(node.textValue());
            } catch (NumberFormatException ignored) {
                throw failure("响应标识越界");
            }
        }

        throw failure("响应标识无效");
    }

    /** 解析版本从零开始，不能套用业务 ID 必须为正数的规则。 */
    private int parseVersion(JsonNode node) {
        if (node != null && ((node.isIntegralNumber() && node.canConvertToInt() && node.intValue() == 0)
                || (node.isTextual() && "0".equals(node.textValue())))) {
            return 0;
        }

        long value = positiveLong(node);

        if (value > Integer.MAX_VALUE) throw failure("解析版本越界");

        return (int) value;
    }

    /** 检查服务端可信业务标识，避免构造无效过滤表达式。 */
    private void requirePositive(Long value) {
        if (value == null || value < 1) throw failure("业务标识无效");
    }

    /** 不将缺失或字符串布尔值视为明确关闭。 */
    private boolean isFalse(JsonNode node) {
        return node != null && node.isBoolean() && !node.booleanValue();
    }

    /** 创建不携带正文、凭据或远端错误消息的异常。 */
    private RetrievalException failure(String message) {
        return new RetrievalException("Milvus：" + message);
    }
}
