package asia.creat.retrieval;

import asia.creat.common.exception.BusinessException;
import asia.creat.entity.SpaceMember;
import asia.creat.mapper.DocumentContentMapper;
import asia.creat.mapper.SpaceMapper;
import asia.creat.mapper.SpaceMemberMapper;
import asia.creat.model.AiDependencyHealth;
import asia.creat.security.LoginUser;
import asia.creat.service.ChunkIndex;
import asia.creat.service.impl.DocumentChunkQueryServiceImpl;
import asia.creat.vo.ChunkHitVO;
import asia.creat.vo.ChunkIndexHit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import static com.baomidou.mybatisplus.extension.toolkit.ChainWrappers.lambdaQueryChain;

@Service
@Slf4j
@ConditionalOnProperty(prefix = "teamdocs.retrieval", name = "hybrid-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class HybridChunkSearch {
    private static final int CANDIDATES = 20;
    @Nullable
    private final AiDependencyHealth health;

    private final DocumentContentMapper chunks;
    private final ChunkIndex keywords;
    private final SiliconFlowEmbeddingClient embeddings;
    private final MilvusVectorClient vectors;
    private final SiliconFlowRerankClient reranker;
    private final SpaceMapper spaces;
    private final SpaceMemberMapper members;

    /** 判断是否配置了可用的语义召回或重排功能。 */
    public boolean enabled() {
        return (embeddings.enabled() && vectors.enabled()) || reranker.enabled();
    }

    /**
     * 融合关键词与向量候选，重排后返回当前仍可读的正文。
     * @param spaceId 空间ID
     * @param documentId 可选文档ID
     * @param query 原始查询
     * @param user 当前用户
     * @param limit 最终数量上限
     * @return 经权限和版本复核的片段
     */
    public List<ChunkHitVO> search(Long spaceId, Long documentId, String query, LoginUser user, int limit) {
        if (query == null || query.isBlank() || query.length() > 2000) {
            throw new BusinessException("搜索内容不能为空且不能超过2000字符");
        }

        checkAccess(spaceId, user);

        List<ChunkHitVO> lexical = lexical(spaceId, documentId, query);
        List<ChunkHitVO> semantic = new ArrayList<>();

        if (embeddings.enabled() && vectors.enabled()) {
            checkAccess(spaceId, user);

            boolean embedded = false;
            try {
                List<Float> vector = embeddings.embed(List.of(query)).get(0);

                embedded = true;
                if (health != null) health.retrievalResult("embedding", true);
                RetrievalContext.check();

                var semanticHits = vectors.search(spaceId, documentId, vector, CANDIDATES);
                if (health != null) {
                    health.retrievalResult("milvus", true);
                }
                for (ChunkIndexHit hit : semanticHits) {
                    ChunkHitVO current = resolve(spaceId, documentId, hit);

                    if (current != null) {
                        semantic.add(current);
                    }
                }
            } catch (RetrievalException e) {
                if (health != null) health.retrievalResult(embedded ? "milvus" : "embedding", false);
                log.warn("向量召回不可用，保留关键词结果: {}", e.getClass().getSimpleName());
            }
        }

        List<ChunkHitVO> candidates = refresh(spaceId, documentId, fuse(lexical, semantic));

        if (reranker.enabled() && !candidates.isEmpty()) {
            checkAccess(spaceId, user);
            // 重排可能影响最终答案，所有出站候选都属于历史来源依赖。
            candidates.forEach(RetrievalContext::record);

            List<String> texts = candidates.stream().map(hit -> excerpt(hit.getExcerpt())).toList();

            try {
                List<Integer> order = reranker.rerank(query, texts, candidates.size());
                List<ChunkHitVO> ranked = new ArrayList<>();

                for (int index : order) {
                    ranked.add(candidates.get(index));
                }

                candidates = ranked;
                if (health != null) health.retrievalResult("reranker", true);
            } catch (RetrievalException e) {
                if (health != null) health.retrievalResult("reranker", false);
                log.warn("正文重排不可用，保留融合排名: {}", e.getClass().getSimpleName());
            }
        }

        checkAccess(spaceId, user);

        return refresh(spaceId, documentId, candidates).stream().limit(Math.min(6, Math.max(1, limit))).toList();
    }

    /** 使用关键词索引召回候选，缺失部分由 MySQL 补足。 */
    private List<ChunkHitVO> lexical(Long spaceId, Long documentId, String rawQuery) {
        String query;

        try {
            query = DocumentChunkQueryServiceImpl.matchQuery(rawQuery);
        } catch (BusinessException e) {
            // 单字无法生成 ngram 查询，但不妨碍独立的语义召回。
            return List.of();
        }

        Map<Long, ChunkHitVO> result = new LinkedHashMap<>();

        if (keywords.enabled()) {
            List<ChunkIndexHit> found = List.of();

            try {
                found = keywords.searchCandidates(spaceId, documentId, query.replace("\"", ""), CANDIDATES);
            } catch (RuntimeException e) {
                log.warn("关键词索引不可用，改用 MySQL: {}", e.getClass().getSimpleName());
            }

            for (ChunkIndexHit candidate : found) {
                ChunkHitVO hit = resolve(spaceId, documentId, candidate);

                if (hit != null) {
                    result.putIfAbsent(hit.getChunkId(), hit);
                }
            }
        }

        if (result.size() < CANDIDATES) {
            List<ChunkHitVO> fallback = documentId == null
                    ? chunks.searchChunks(spaceId, query, CANDIDATES)
                    : chunks.searchChunksInDocument(spaceId, documentId, query, CANDIDATES);

            for (ChunkHitVO hit : fallback) {
                if (result.size() >= CANDIDATES) {
                    break;
                }

                result.putIfAbsent(hit.getChunkId(), hit);
            }
        }

        return new ArrayList<>(result.values());
    }

    /** 使用排名而非混加 BM25 与余弦分数；相同分数按分块 ID 稳定排序。 */
    static List<ChunkHitVO> fuse(List<ChunkHitVO> lexical, List<ChunkHitVO> semantic) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        Map<Long, ChunkHitVO> rows = new LinkedHashMap<>();

        for (List<ChunkHitVO> list : List.of(lexical, semantic)) {
            Set<Long> seen = new HashSet<>();
            int rank = 0;

            for (ChunkHitVO hit : list) {
                if (!seen.add(hit.getChunkId())) {
                    continue;
                }

                rank++;
                rows.putIfAbsent(hit.getChunkId(), hit);
                scores.merge(hit.getChunkId(), 1.0 / (60 + rank), Double::sum);
            }
        }

        return scores.keySet().stream()
                .sorted(Comparator.<Long>comparingDouble(scores::get).reversed().thenComparingLong(Long::longValue))
                .limit(CANDIDATES).map(rows::get).toList();
    }

    /** 在出站和返回前重新读取权威正文，丢弃已失效候选。 */
    private List<ChunkHitVO> refresh(Long spaceId, Long documentId, List<ChunkHitVO> candidates) {
        List<ChunkHitVO> result = new ArrayList<>();

        for (ChunkHitVO hit : candidates) {
            ChunkHitVO current = resolve(spaceId, documentId,
                    new ChunkIndexHit(hit.getChunkId(), hit.getDocumentId(), hit.getParseVersion(), null));

            if (current != null) {
                result.add(current);
            }
        }

        return result;
    }

    /** 按空间、文档与解析版本复核索引候选。 */
    private ChunkHitVO resolve(Long spaceId, Long documentId, ChunkIndexHit candidate) {
        if (candidate.getChunkId() == null || candidate.getDocumentId() == null || candidate.getParseVersion() == null
                || (documentId != null && !documentId.equals(candidate.getDocumentId()))) {
            return null;
        }

        return chunks.findReadableChunk(spaceId, candidate.getDocumentId(), candidate.getChunkId(), candidate.getParseVersion());
    }

    /** 检查当前运行与空间成员资格，失权时不继续降级执行。 */
    private void checkAccess(Long spaceId, LoginUser user) {
        RetrievalContext.check();

        if (user == null || spaces.selectById(spaceId) == null
                || lambdaQueryChain(members)
                        .eq(SpaceMember::getSpaceId, spaceId).eq(SpaceMember::getUserId, user.getUserId()).one() == null) {
            throw new BusinessException("空间不存在或成员权限已失效");
        }
    }

    /** 限制重排正文长度，避免在 UTF-16 代理对中间截断。 */
    private String excerpt(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("正文分块为空");
        }

        int end = Math.min(value.length(), 2000);

        if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }

        return value.substring(0, end);
    }
}
