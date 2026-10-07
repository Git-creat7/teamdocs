package asia.creat.retrieval;

import asia.creat.config.EmbeddingProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class SiliconFlowEmbeddingClient {
    private static final Set<Integer> DIMENSIONS = Set.of(64, 128, 256, 512, 768, 1024, 1536, 2048, 2560, 4096);

    private final EmbeddingProperties properties;
    private final RetrievalHttp http;

    /**
     * 检查开关、出站授权与凭据。
     * @return 是否允许向量化请求
     */
    public boolean enabled() {
        return properties.isEnabled() && properties.isAllowDocumentEgress()
                && RetrievalHttp.hasApiKey(properties.getApiKey());
    }

    /**
     * 批量向量化并校验输入与输出对应关系。
     * @param texts 待向量化文本
     * @return 按输入顺序排列的向量
     */
    public List<List<Float>> embed(List<String> texts) {
        if (!enabled()) {
            throw new RetrievalException("向量化未启用、未授权或缺少有效凭据");
        }

        if (texts == null || texts.isEmpty() || texts.size() > 16) {
            throw new RetrievalException("向量化批次必须包含 1 到 16 段文本");
        }

        for (String text : texts) {
            if (text == null || text.isBlank() || text.length() > 16000) {
                throw new RetrievalException("向量化文本为空或超过长度上限");
            }
        }

        if (properties.getModelName() == null || properties.getModelName().isBlank()
                || !DIMENSIONS.contains(properties.getDimensions())) {
            throw new RetrievalException("向量化模型或维度配置无效");
        }

        List<String> input = List.copyOf(texts);
        JsonNode response = http.post(properties.getBaseUrl(), "/embeddings", properties.getApiKey(),
                Map.of("model", properties.getModelName(), "input", input,
                        "dimensions", properties.getDimensions(), "encoding_format", "float"),
                properties.getTimeoutSeconds());
        JsonNode data = response.get("data");

        if (data == null || !data.isArray() || data.size() != input.size()) {
            throw new RetrievalException("向量化返回条数不匹配");
        }

        List<List<Float>> vectors = new ArrayList<>(Collections.nCopies(input.size(), null));

        for (JsonNode item : data) {
            JsonNode index = item.get("index");

            if (index == null || !index.isIntegralNumber() || !index.canConvertToInt()
                    || index.intValue() < 0 || index.intValue() >= input.size()
                    || vectors.get(index.intValue()) != null) {
                throw new RetrievalException("向量化返回索引无效");
            }

            JsonNode values = item.get("embedding");

            if (values == null || !values.isArray() || values.size() != properties.getDimensions()) {
                throw new RetrievalException("向量化返回维度不匹配");
            }

            List<Float> vector = new ArrayList<>(values.size());
            double norm = 0;

            for (JsonNode value : values) {
                if (!value.isNumber() || !Float.isFinite(value.floatValue())) {
                    throw new RetrievalException("向量化返回非有限数值");
                }

                float number = value.floatValue();

                vector.add(number);
                norm += (double) number * number;
            }

            if (norm == 0) {
                throw new RetrievalException("向量化返回零向量");
            }

            vectors.set(index.intValue(), List.copyOf(vector));
        }

        // 索引经过唯一性与数量校验，顺序仅由 index 决定。
        return List.copyOf(vectors);
    }
}
