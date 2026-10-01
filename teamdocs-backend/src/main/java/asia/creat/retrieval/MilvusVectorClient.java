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

    /** 在指定空间和可选文档内召回候选；正文与权限仍由 MySQL 复核。 */
    public List<ChunkIndexHit> search(Long spaceId, Long documentId, List<Float> vector, int limit) {
        requirePositive(spaceId);
        if (documentId != null) requirePositive(documentId);
        validateVector(vector);
        if (limit < 1 || limit > 20) throw failure("候选数量越界");
        validateSchema(success(post("collections/describe", Map.of())));

        String filter = "space_id == " + spaceId;
        if (documentId != null) filter += " and document_id == " + documentId;
        JsonNode data = success(post("entities/search", Map.of(
                "data", List.of(vector), "annsField", "embedding", "filter", filter,
                "limit", limit, "consistencyLevel", "Strong",
                "outputFields", List.of("chunk_id", "document_id", "space_id", "parse_version"),
                "searchParams", Map.of("metricType", "COSINE", "params", Map.of()))));
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
        validateSchema(success(post("collections/describe", Map.of())));
        JsonNode data = success(post("entities/upsert", Map.of("data", rows)));
        if (positiveLong(data.get("upsertCount")) != chunks.size()) throw failure("向量写入数量不一致");
    }

    /** 清理指定文档的向量；集合不存在时视为已经清理，不创建集合。 */
    public void deleteDocument(Long documentId) {
        requirePositive(documentId);
        JsonNode description = post("collections/describe", Map.of());
        if (code(description) == 100) return;
        validateSchema(success(description));
        success(post("entities/delete", Map.of("filter", "document_id == " + documentId)));
    }

    /** 建立或核对专用集合及索引，拒绝修改不兼容的已有集合。 */
    public synchronized void ensureCollection() {
        JsonNode description = post("collections/describe", Map.of());
        if (code(description) == 100) {
            JsonNode created = post("collections/create", Map.of(
                    "description", signature(), "schema", schema(),
                    "params", Map.of("consistencyLevel", "Strong")));
            if (code(created) != 0) {
                String message = created.path("message").asText("").toLowerCase(Locale.ROOT);
                if (code(created) != 1100 || !message.contains("already exist")) success(created);
            }
            description = post("collections/describe", Map.of());
        }
        JsonNode data = success(description);
        validateSchema(data);
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
            success(post("indexes/create", Map.of("indexParams", List.of(Map.of(
                    "fieldName", "embedding", "indexName", INDEX_NAME,
                    "metricType", "COSINE", "indexType", "FLAT", "params", Map.of())))));
        }
        success(post("collections/load", Map.of()));
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
    private void validateSchema(JsonNode data) {
        if (!data.isObject() || !properties.getCollection().equals(data.path("collectionName").asText())
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

    /** 发送固定 REST 操作，集合名不接受表达式或路径字符。 */
    private JsonNode post(String operation, Map<String, ?> parameters) {
        if (!enabled()) throw failure("向量索引未启用");
        String collection = properties.getCollection();
        if (collection == null || !collection.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
            throw failure("集合名称无效");
        }
        signature();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("collectionName", collection);
        request.putAll(parameters);
        return http.post(properties.getUrl(), PREFIX + operation, properties.getToken(), request,
                properties.getTimeoutSeconds());
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
