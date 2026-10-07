package asia.creat.retrieval;

import asia.creat.config.RerankProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class SiliconFlowRerankClient {
    private final RerankProperties properties;
    private final RetrievalHttp http;

    /**
     * 检查开关、出站授权与凭据。
     * @return 是否允许重排请求
     */
    public boolean enabled() {
        return properties.isEnabled() && properties.isAllowDocumentEgress()
                && RetrievalHttp.hasApiKey(properties.getApiKey());
    }

    /**
     * 根据问题重排已经授权的候选正文。
     * @param query 查询文本
     * @param documents 候选正文
     * @param topN 返回数量
     * @return 按相关性排列的原始候选索引
     */
    public List<Integer> rerank(String query, List<String> documents, int topN) {
        if (!enabled()) {
            throw new RetrievalException("重排未启用、未授权或缺少有效凭据");
        }

        if (query == null || query.isBlank() || query.length() > 2000
                || documents == null || documents.isEmpty() || documents.size() > 20
                || topN < 1 || topN > documents.size()) {
            throw new RetrievalException("重排查询、候选数量或返回数量无效");
        }

        for (String document : documents) {
            if (document == null || document.isBlank() || document.length() > 2000) {
                throw new RetrievalException("重排候选为空或超过长度上限");
            }
        }

        if (properties.getModelName() == null || properties.getModelName().isBlank()) {
            throw new RetrievalException("重排模型配置无效");
        }

        List<String> input = List.copyOf(documents);
        JsonNode response = http.post(properties.getBaseUrl(), "/rerank", properties.getApiKey(),
                Map.of("model", properties.getModelName(), "query", query, "documents", input,
                        "top_n", topN, "return_documents", false,
                        "instruction", "Rank passages by how directly they provide evidence to answer the team document question."),
                properties.getTimeoutSeconds());
        JsonNode results = response.get("results");

        if (results == null || !results.isArray() || results.size() != topN) {
            throw new RetrievalException("重排返回条数不匹配");
        }

        List<Integer> order = new ArrayList<>(topN);
        Set<Integer> seen = new HashSet<>();

        for (JsonNode item : results) {
            JsonNode index = item.get("index");
            JsonNode score = item.get("relevance_score");

            if (index == null || !index.isIntegralNumber() || !index.canConvertToInt()
                    || index.intValue() < 0 || index.intValue() >= input.size()
                    || !seen.add(index.intValue()) || score == null || !score.isNumber()
                    || !Double.isFinite(score.doubleValue())) {
                throw new RetrievalException("重排返回索引或分数无效");
            }

            order.add(index.intValue());
        }

        // 只返回已校验的输入索引，不采纳远端回传的正文。
        return List.copyOf(order);
    }
}
