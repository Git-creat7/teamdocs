package asia.creat.retrieval;

import asia.creat.agent.AgentData;
import asia.creat.common.exception.BusinessException;
import asia.creat.config.RetrievalProperties;
import asia.creat.entity.SpaceMember;
import asia.creat.mapper.DocumentContentMapper;
import asia.creat.mapper.SpaceMapper;
import asia.creat.mapper.SpaceMemberMapper;
import asia.creat.mapper.VectorIndexMapper;
import asia.creat.model.AiDependencyHealth;
import asia.creat.security.LoginUser;
import asia.creat.service.ChunkIndex;
import asia.creat.service.impl.DocumentChunkQueryServiceImpl;
import asia.creat.vo.ChunkHitVO;
import asia.creat.vo.ChunkIndexHit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import static com.baomidou.mybatisplus.extension.toolkit.ChainWrappers.lambdaQueryChain;

@Service
@Slf4j
@ConditionalOnProperty(prefix = "teamdocs.retrieval", name = "hybrid-enabled", havingValue = "true", matchIfMissing = true)
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
    private final RetrievalProperties retrievalProperties;
    private final ExecutorService retrievalExecutor;
    @Nullable
    private final VectorIndexMapper vectorIndexMapper;

    @Autowired
    public HybridChunkSearch(
            @Nullable AiDependencyHealth health,
            DocumentContentMapper chunks,
            ChunkIndex keywords,
            SiliconFlowEmbeddingClient embeddings,
            MilvusVectorClient vectors,
            SiliconFlowRerankClient reranker,
            SpaceMapper spaces,
            SpaceMemberMapper members,
            RetrievalProperties retrievalProperties,
            @Nullable @Qualifier("retrievalExecutor") ExecutorService retrievalExecutor,
            @Nullable VectorIndexMapper vectorIndexMapper) {
        this.health = health;
        this.chunks = chunks;
        this.keywords = keywords;
        this.embeddings = embeddings;
        this.vectors = vectors;
        this.reranker = reranker;
        this.spaces = spaces;
        this.members = members;
        this.retrievalProperties = retrievalProperties != null ? retrievalProperties : new RetrievalProperties();
        this.retrievalExecutor = retrievalExecutor != null ? retrievalExecutor : defaultExecutor();
        this.vectorIndexMapper = vectorIndexMapper;
    }

    public HybridChunkSearch(
            @Nullable AiDependencyHealth health,
            DocumentContentMapper chunks,
            ChunkIndex keywords,
            SiliconFlowEmbeddingClient embeddings,
            MilvusVectorClient vectors,
            SiliconFlowRerankClient reranker,
            SpaceMapper spaces,
            SpaceMemberMapper members,
            RetrievalProperties retrievalProperties,
            @Nullable @Qualifier("retrievalExecutor") ExecutorService retrievalExecutor) {
        this(health, chunks, keywords, embeddings, vectors, reranker, spaces, members, retrievalProperties, retrievalExecutor, null);
    }

    public HybridChunkSearch(
            @Nullable AiDependencyHealth health,
            DocumentContentMapper chunks,
            ChunkIndex keywords,
            SiliconFlowEmbeddingClient embeddings,
            MilvusVectorClient vectors,
            SiliconFlowRerankClient reranker,
            SpaceMapper spaces,
            SpaceMemberMapper members) {
        this(health, chunks, keywords, embeddings, vectors, reranker, spaces, members, new RetrievalProperties(), null, null);
    }

    private static ExecutorService defaultExecutor() {
        return new ThreadPoolExecutor(
                2, 8,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(16),
                new ThreadFactory() {
                    private final AtomicInteger count = new AtomicInteger();
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "retrieval-async-" + count.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /** 判断是否配置了可用的语义召回或重排功能。 */
    public boolean enabled() {
        return (embeddings.enabled() && vectors.enabled()) || reranker.enabled();
    }

    /**
     * 语义分支异步执行结果：将召回候选片段与依赖元数据打包返回主线程，
     * 避免异步线程直接修改主线程依赖集合。
     */
    public record SemanticBranchResult(
            List<ChunkHitVO> candidates,
            List<ChunkHitVO> dependencyHits,
            List<AgentData.Dependency> dependencies) {
        public static SemanticBranchResult empty() {
            return new SemanticBranchResult(List.of(), List.of(), List.of());
        }
    }

    /**
     * 单查询兼容重载。
     */
    public List<ChunkHitVO> search(Long spaceId, Long documentId, String query, LoginUser user, int limit) {
        return search(spaceId, documentId, query, query, user, limit);
    }

    /**
     * 双参数检索：融合关键词词法与向量语义候选，重排后返回当前仍可读的正文。
     * <p>
     * 版本切换期间（V1 -> V2）的检索策略：
     * <ul>
     *   <li>重解析发布后，{@code document.parse_version} 与 {@code document_content} 立即推进到 V2，词法检索（ES/MySQL）立即服务 V2。</li>
     *   <li>在新向量集合（V2）完成蓝绿构建与 {@code completeAndSwitch} 切换前，向量路径中的旧版（V1）或未切换新版（V2）候选会被 {@link #resolveVectorCandidate} 过滤，
     *       既不会返回旧版脏引用，也不会触发 {@code SOURCE_CHANGED} 崩溃，且融合器自动以词法结果（权重 1.0）服务 V2。</li>
     *   <li>新向量集合切换完成后，{@code active_collection} 与 {@code active_parse_version} 同步指向 V2，立即恢复词法+语义混合检索。</li>
     * </ul>
     *
     * @param spaceId 空间ID
     * @param documentId 可选文档ID
     * @param keyword 关键词（用于词法路径）
     * @param semanticQuery 自然语言问题（用于向量化与重排）
     * @param user 当前用户
     * @param limit 最终数量上限
     * @return 经权限、版本复核与多样性门控的片段
     */
    public List<ChunkHitVO> search(Long spaceId, Long documentId, String keyword, String semanticQuery, LoginUser user, int limit) {
        if (keyword == null || keyword.isBlank() || keyword.length() > 2000) {
            throw new BusinessException("搜索内容不能为空且不能超过2000字符");
        }

        String actualSemanticQuery = semanticQuery != null && !semanticQuery.isBlank()
                ? (semanticQuery.length() > 2000 ? semanticQuery.substring(0, 2000) : semanticQuery)
                : keyword;

        checkAccess(spaceId, user);

        AtomicBoolean searchCancelled = new AtomicBoolean(false);
        CompletableFuture<SemanticBranchResult> semanticFuture = null;

        try {
            if (embeddings.enabled() && vectors.enabled()) {
                checkAccess(spaceId, user);

                Supplier<SemanticBranchResult> semanticTask = RetrievalContext.wrap(() -> {
                    RetrievalContext.check();
                    boolean embedded = false;
                    try {
                        RetrievalContext.check();
                        List<Float> vector = embeddings.embed(List.of(actualSemanticQuery)).get(0);
                        embedded = true;
                        if (health != null) health.retrievalResult("embedding", true);

                        RetrievalContext.check();
                        var semanticHits = searchMilvusCandidates(spaceId, documentId, vector);
                        if (health != null) health.retrievalResult("milvus", true);

                        RetrievalContext.check();
                        List<ChunkHitVO> hits = new ArrayList<>();
                        List<ChunkHitVO> depHits = new ArrayList<>();
                        List<AgentData.Dependency> deps = new ArrayList<>();
                        for (ChunkIndexHit hit : semanticHits) {
                            RetrievalContext.check();
                            ChunkHitVO current = resolveVectorCandidate(spaceId, documentId, hit);
                            if (current != null) {
                                hits.add(current);
                                depHits.add(current);
                                deps.add(new AgentData.Dependency(
                                        current.getDocumentId(),
                                        current.getParseVersion(),
                                        "READY",
                                        current.getDocumentName(),
                                        null));
                            }
                        }
                        return new SemanticBranchResult(hits, depHits, deps);
                    } catch (RetrievalException e) {
                        if (health != null) health.retrievalResult(embedded ? "milvus" : "embedding", false);
                        log.warn("向量召回不可用，保留关键词结果: {}", e.getClass().getSimpleName());
                        return SemanticBranchResult.empty();
                    }
                }, searchCancelled::get);

                try {
                    semanticFuture = CompletableFuture.supplyAsync(semanticTask, retrievalExecutor);
                } catch (RejectedExecutionException e) {
                    log.warn("检索线程池饱和，直接降级为词法检索: {}", e.getMessage());
                    if (health != null) health.retrievalResult("embedding", false);
                    semanticFuture = CompletableFuture.completedFuture(SemanticBranchResult.empty());
                }
            }

            // 主线程执行词法检索，受 RetrievalContext 约束
            List<ChunkHitVO> lexical = lexical(spaceId, documentId, keyword);
            RetrievalContext.check();

            List<ChunkHitVO> semantic = new ArrayList<>();
            List<ChunkHitVO> semanticDependencyHits = new ArrayList<>();
            if (semanticFuture != null) {
                long remaining = RetrievalContext.remainingMillis();
                long reserveMs = 100L;
                if (remaining <= reserveMs) {
                    searchCancelled.set(true);
                    semanticFuture.cancel(true);
                } else {
                    try {
                        long waitBudget = Math.min(3000L, remaining - reserveMs);
                        SemanticBranchResult branchResult = semanticFuture.get(waitBudget, TimeUnit.MILLISECONDS);
                        if (!searchCancelled.get() && branchResult != null) {
                            RetrievalContext.check();
                            semantic = branchResult.candidates() != null ? branchResult.candidates() : List.of();
                            semanticDependencyHits = branchResult.dependencyHits() != null ? branchResult.dependencyHits() : List.of();
                        }
                    } catch (TimeoutException e) {
                        log.warn("向量召回超时，降级为词法检索");
                        searchCancelled.set(true);
                        semanticFuture.cancel(true);
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof RuntimeException) {
                            RetrievalContext.check();
                            if (cause instanceof BusinessException || cause instanceof IllegalStateException) {
                                throw (RuntimeException) cause;
                            }
                        }
                        log.warn("向量召回异常，保留词法结果: {}", cause != null ? cause.getMessage() : e.getMessage());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        searchCancelled.set(true);
                        throw new IllegalStateException("检索被中断", e);
                    }
                }
            }

            checkAccess(spaceId, user);

            double wLex = isPreciseKeyword(keyword) ? 0.6 : 0.5;
            double wSem = isPreciseKeyword(keyword) ? 0.4 : 0.5;
            List<ChunkHitVO> candidates = refresh(spaceId, documentId, fuse(lexical, semantic, wLex, wSem));

            // 主线程确认结果有效且未取消后，统一合并语义分支候选依赖
            List<ChunkHitVO> verifiedSemanticDeps = semanticDependencyHits.isEmpty()
                    ? List.of()
                    : refresh(spaceId, documentId, semanticDependencyHits);

            if (reranker.enabled() && !candidates.isEmpty()) {
                checkAccess(spaceId, user);
                Set<Long> recordedChunkIds = new HashSet<>();
                for (ChunkHitVO candidate : candidates) {
                    RetrievalContext.record(candidate);
                    recordedChunkIds.add(candidate.getChunkId());
                }
                for (ChunkHitVO semHit : verifiedSemanticDeps) {
                    if (recordedChunkIds.add(semHit.getChunkId())) {
                        RetrievalContext.record(semHit);
                    }
                }

                List<String> texts = candidates.stream().map(hit -> {
                    StringBuilder sb = new StringBuilder();
                    if (hit.getDocumentName() != null && !hit.getDocumentName().isBlank()) {
                        sb.append("【文档: ").append(hit.getDocumentName());
                        if (hit.getHeading() != null && !hit.getHeading().isBlank()) {
                            sb.append(" > ").append(hit.getHeading());
                        }
                        sb.append("】\n");
                    }
                    return excerpt(sb + (hit.getExcerpt() != null ? hit.getExcerpt() : ""));
                }).toList();

                try {
                    List<Integer> order = reranker.rerank(actualSemanticQuery, texts, candidates.size());
                    if (order != null && order.size() == candidates.size()) {
                        List<ChunkHitVO> ranked = new ArrayList<>();
                        for (int index : order) {
                            if (index >= 0 && index < candidates.size()) {
                                ranked.add(candidates.get(index));
                            }
                        }
                        if (ranked.size() == candidates.size()) {
                            candidates = ranked;
                        }
                    }
                    if (health != null) health.retrievalResult("reranker", true);
                } catch (RetrievalException e) {
                    if (health != null) health.retrievalResult("reranker", false);
                    log.warn("正文重排不可用，保留融合排名: {}", e.getClass().getSimpleName());
                }
            } else if (!verifiedSemanticDeps.isEmpty()) {
                checkAccess(spaceId, user);
                for (ChunkHitVO semHit : verifiedSemanticDeps) {
                    RetrievalContext.record(semHit);
                }
            }

            checkAccess(spaceId, user);

            int targetLimit = Math.min(6, Math.max(1, limit));
            List<ChunkHitVO> finalHits = applyDiversity(refresh(spaceId, documentId, candidates), targetLimit, 2);

            // Small-to-Big 上下文扩展
            expandAdjacentContext(spaceId, finalHits);

            checkAccess(spaceId, user);

            return finalHits;
        } finally {
            searchCancelled.set(true);
            if (semanticFuture != null) {
                semanticFuture.cancel(true);
            }
        }
    }

    /**
     * 按当前已激活的蓝绿集合路由向量查询；未配置任务表或未指定独立集合时走默认集合。
     */
    private List<ChunkIndexHit> searchMilvusCandidates(Long spaceId, Long documentId, List<Float> vector) {
        if (vectorIndexMapper == null) {
            return vectors.search(spaceId, documentId, vector, CANDIDATES);
        }

        if (documentId != null) {
            VectorIndexMapper.Task task = RetrievalContext.executeJdbc(() -> vectorIndexMapper.task(documentId));
            if (task == null || task.getActiveGeneration() <= 0 || task.getActiveParseVersion() == null) {
                return List.of();
            }
            if (task.getActiveCollection() != null && !task.getActiveCollection().isBlank()) {
                List<ChunkIndexHit> byCollection = vectors.search(task.getActiveCollection(), spaceId, documentId, vector, CANDIDATES);
                if (byCollection != null && !byCollection.isEmpty()) {
                    return byCollection;
                }
            }
            return vectors.search(spaceId, documentId, vector, CANDIDATES);
        }

        List<VectorIndexMapper.Task> activeTasks = RetrievalContext.executeJdbc(() -> vectorIndexMapper.activeTasksBySpace(spaceId));
        if (activeTasks != null && !activeTasks.isEmpty()) {
            Set<String> collections = new LinkedHashSet<>();
            for (VectorIndexMapper.Task task : activeTasks) {
                if (task.getActiveCollection() != null && !task.getActiveCollection().isBlank()) {
                    collections.add(task.getActiveCollection());
                }
            }
            if (!collections.isEmpty()) {
                List<ChunkIndexHit> merged = new ArrayList<>();
                Set<Long> seen = new HashSet<>();
                for (String collection : collections) {
                    RetrievalContext.check();
                    List<ChunkIndexHit> hits = vectors.search(collection, spaceId, null, vector, CANDIDATES);
                    if (hits != null) {
                        for (ChunkIndexHit hit : hits) {
                            if (hit != null && hit.getChunkId() != null && seen.add(hit.getChunkId())) {
                                merged.add(hit);
                                if (merged.size() >= CANDIDATES) {
                                    return merged;
                                }
                            }
                        }
                    }
                }
                if (!merged.isEmpty()) {
                    return merged;
                }
            }
        }

        return vectors.search(spaceId, null, vector, CANDIDATES);
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
                RetrievalContext.check();
                found = keywords.searchCandidates(spaceId, documentId, query.replace("\"", ""), CANDIDATES);
                RetrievalContext.check();
            } catch (RuntimeException e) {
                RetrievalContext.check();
                if (e instanceof BusinessException) {
                    throw e;
                }
                log.warn("关键词索引不可用，改用 MySQL: {}", e.getClass().getSimpleName());
            }

            for (ChunkIndexHit candidate : found) {
                RetrievalContext.check();
                ChunkHitVO hit = resolve(spaceId, documentId, candidate);

                if (hit != null) {
                    result.putIfAbsent(hit.getChunkId(), hit);
                }
            }
        }

        if (result.size() < CANDIDATES) {
            List<ChunkHitVO> fallback = RetrievalContext.executeJdbc(() -> documentId == null
                    ? chunks.searchChunks(spaceId, query, CANDIDATES)
                    : chunks.searchChunksInDocument(spaceId, documentId, query, CANDIDATES));

            for (ChunkHitVO hit : fallback) {
                if (result.size() >= CANDIDATES) {
                    break;
                }

                result.putIfAbsent(hit.getChunkId(), hit);
            }
        }

        return new ArrayList<>(result.values());
    }

    /** 动态加权倒数排名融合（RRF）。 */
    public static List<ChunkHitVO> fuse(List<ChunkHitVO> lexical, List<ChunkHitVO> semantic) {
        return fuse(lexical, semantic, 0.5, 0.5);
    }

    public static List<ChunkHitVO> fuse(List<ChunkHitVO> lexical, List<ChunkHitVO> semantic, double weightLex, double weightSem) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        Map<Long, ChunkHitVO> rows = new LinkedHashMap<>();

        boolean lexEmpty = lexical == null || lexical.isEmpty();
        boolean semEmpty = semantic == null || semantic.isEmpty();

        double wLex = semEmpty ? 1.0 : (lexEmpty ? 0.0 : weightLex);
        double wSem = lexEmpty ? 1.0 : (semEmpty ? 0.0 : weightSem);

        if (!lexEmpty) {
            Set<Long> seen = new HashSet<>();
            int rank = 0;
            for (ChunkHitVO hit : lexical) {
                if (!seen.add(hit.getChunkId())) {
                    continue;
                }
                rank++;
                rows.putIfAbsent(hit.getChunkId(), hit);
                scores.merge(hit.getChunkId(), wLex * (1.0 / (60 + rank)), Double::sum);
            }
        }

        if (!semEmpty) {
            Set<Long> seen = new HashSet<>();
            int rank = 0;
            for (ChunkHitVO hit : semantic) {
                if (!seen.add(hit.getChunkId())) {
                    continue;
                }
                rank++;
                rows.putIfAbsent(hit.getChunkId(), hit);
                scores.merge(hit.getChunkId(), wSem * (1.0 / (60 + rank)), Double::sum);
            }
        }

        return scores.keySet().stream()
                .sorted(Comparator.<Long>comparingDouble(scores::get).reversed().thenComparingLong(Long::longValue))
                .limit(CANDIDATES)
                .map(rows::get)
                .toList();
    }

    /** 文档多样性门控：单篇文档最多保留 maxPerDoc 个切片，除非剩余配额无法由其他文档填满。 */
    public static List<ChunkHitVO> applyDiversity(List<ChunkHitVO> candidates, int limit, int maxPerDoc) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<ChunkHitVO> selected = new ArrayList<>();
        List<ChunkHitVO> deferred = new ArrayList<>();
        Map<Long, Integer> docCount = new LinkedHashMap<>();

        for (ChunkHitVO hit : candidates) {
            Long docId = hit.getDocumentId();
            int count = docCount.getOrDefault(docId, 0);
            if (count < maxPerDoc) {
                selected.add(hit);
                docCount.put(docId, count + 1);
                if (selected.size() == limit) {
                    break;
                }
            } else {
                deferred.add(hit);
            }
        }

        if (selected.size() < limit) {
            for (ChunkHitVO hit : deferred) {
                selected.add(hit);
                if (selected.size() == limit) {
                    break;
                }
            }
        }

        return selected;
    }

    /** Small-to-Big 上下文扩展：在独立预算内扩展相邻块内容，不跨章节且严格去重 overlap。 */
    private void expandAdjacentContext(Long spaceId, List<ChunkHitVO> hits) {
        int budget = retrievalProperties.getExpandMaxChars();
        if (budget <= 0) {
            return;
        }

        for (ChunkHitVO hit : hits) {
            if (hit.getChunkIndex() == null || hit.getParseVersion() == null) {
                continue;
            }

            // 1. 向前扩展相邻块
            if (hit.getChunkIndex() > 0) {
                ChunkHitVO prev = RetrievalContext.executeJdbc(() -> chunks.findReadableChunkByIndex(
                        spaceId, hit.getDocumentId(), hit.getChunkIndex() - 1, hit.getParseVersion()));
                if (prev != null && Objects.equals(prev.getHeading(), hit.getHeading()) && prev.getExcerpt() != null) {
                    String prevText = prev.getExcerpt();
                    String currText = hit.getExcerpt() != null ? hit.getExcerpt() : "";
                    int overlap = findOverlap(prevText, currText);
                    String nonOverlapping = prevText.substring(0, prevText.length() - overlap);
                    if (!nonOverlapping.isEmpty()) {
                        int take = Math.min(nonOverlapping.length(), budget);
                        String prepend = nonOverlapping.substring(nonOverlapping.length() - take);
                        hit.setExcerpt(prepend + currText);
                        if (hit.getCharStart() != null) {
                            hit.setCharStart(Math.max(0, hit.getCharStart() - prepend.length()));
                        }
                    }
                }
            }

            // 2. 向后扩展相邻块
            ChunkHitVO next = RetrievalContext.executeJdbc(() -> chunks.findReadableChunkByIndex(
                    spaceId, hit.getDocumentId(), hit.getChunkIndex() + 1, hit.getParseVersion()));
            if (next != null && Objects.equals(next.getHeading(), hit.getHeading()) && next.getExcerpt() != null) {
                String currText = hit.getExcerpt() != null ? hit.getExcerpt() : "";
                String nextText = next.getExcerpt();
                int overlap = findOverlap(currText, nextText);
                String nonOverlapping = nextText.substring(overlap);
                if (!nonOverlapping.isEmpty()) {
                    int take = Math.min(nonOverlapping.length(), budget);
                    String append = nonOverlapping.substring(0, take);
                    hit.setExcerpt(currText + append);
                    if (hit.getCharEnd() != null) {
                        hit.setCharEnd(hit.getCharEnd() + append.length());
                    }
                }
            }
        }
    }

    static int findOverlap(String s1, String s2) {
        if (s1 == null || s2 == null || s1.isEmpty() || s2.isEmpty()) return 0;
        int maxCheck = Math.min(Math.min(s1.length(), s2.length()), 120);
        for (int len = maxCheck; len >= 1; len--) {
            if (s1.endsWith(s2.substring(0, len))) {
                return len;
            }
        }
        return 0;
    }

    static boolean isPreciseKeyword(String keyword) {
        if (keyword == null) return false;
        if (keyword.contains("\"")) return true;
        return keyword.matches(".*[a-zA-Z0-9]+_[a-zA-Z0-9]+.*")
                || keyword.matches(".*[a-z]+[A-Z]+[a-zA-Z0-9]*.*")
                || keyword.matches(".*\\.[a-zA-Z0-9]{2,4}\\b.*")
                || keyword.matches(".*[a-zA-Z0-9_]+\\([a-zA-Z0-9_, ]*\\).*");
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

        return RetrievalContext.executeJdbc(() -> chunks.findReadableChunk(
                spaceId, candidate.getDocumentId(), candidate.getChunkId(), candidate.getParseVersion()));
    }

    /** 按空间、文档、活跃代次与解析版本复核向量索引候选，拦截未完成切换的新代次与过期的旧代次。 */
    public ChunkHitVO resolveVectorCandidate(Long spaceId, Long documentId, ChunkIndexHit candidate) {
        if (candidate.getChunkId() == null || candidate.getDocumentId() == null || candidate.getParseVersion() == null
                || (documentId != null && !documentId.equals(candidate.getDocumentId()))) {
            return null;
        }

        if (vectorIndexMapper != null) {
            VectorIndexMapper.Task task = RetrievalContext.executeJdbc(() -> vectorIndexMapper.task(candidate.getDocumentId()));
            if (task == null || task.getActiveGeneration() <= 0
                    || task.getActiveParseVersion() == null
                    || !task.getActiveParseVersion().equals(candidate.getParseVersion())) {
                return null;
            }
        }

        ChunkHitVO hit = RetrievalContext.executeJdbc(() -> chunks.findReadableChunk(
                spaceId, candidate.getDocumentId(), candidate.getChunkId(), candidate.getParseVersion()));
        if (hit == null) {
            return null;
        }
        if (hit.getParseVersion() == null || !hit.getParseVersion().equals(candidate.getParseVersion())) {
            return null;
        }
        return hit;
    }

    /** 检查当前运行与空间成员资格，失权时不继续降级执行。 */
    private void checkAccess(Long spaceId, LoginUser user) {
        RetrievalContext.check();

        if (user == null || RetrievalContext.executeJdbc(() -> spaces.selectById(spaceId)) == null
                || RetrievalContext.executeJdbc(() -> lambdaQueryChain(members)
                        .eq(SpaceMember::getSpaceId, spaceId).eq(SpaceMember::getUserId, user.getUserId()).one()) == null) {
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
