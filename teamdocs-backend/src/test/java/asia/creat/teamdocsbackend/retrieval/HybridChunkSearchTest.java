package asia.creat.teamdocsbackend.retrieval;

import asia.creat.common.exception.BusinessException;
import asia.creat.entity.Space;
import asia.creat.entity.SpaceMember;
import asia.creat.mapper.DocumentContentMapper;
import asia.creat.mapper.SpaceMapper;
import asia.creat.mapper.SpaceMemberMapper;
import asia.creat.mapper.VectorIndexMapper;
import asia.creat.retrieval.HybridChunkSearch;
import asia.creat.retrieval.MilvusVectorClient;
import asia.creat.retrieval.RetrievalContext;
import asia.creat.retrieval.RetrievalException;
import asia.creat.retrieval.SiliconFlowEmbeddingClient;
import asia.creat.retrieval.SiliconFlowRerankClient;
import asia.creat.security.LoginUser;
import asia.creat.service.ChunkIndex;
import asia.creat.vo.ChunkHitVO;
import asia.creat.vo.ChunkIndexHit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import asia.creat.config.RetrievalProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HybridChunkSearchTest {
    private final DocumentContentMapper chunks = mock(DocumentContentMapper.class);
    private final ChunkIndex keywords = mock(ChunkIndex.class);
    private final SiliconFlowEmbeddingClient embeddings = mock(SiliconFlowEmbeddingClient.class);
    private final MilvusVectorClient vectors = mock(MilvusVectorClient.class);
    private final SiliconFlowRerankClient reranker = mock(SiliconFlowRerankClient.class);
    private final SpaceMapper spaces = mock(SpaceMapper.class);
    private final SpaceMemberMapper members = mock(SpaceMemberMapper.class);
    private final LoginUser user = new LoginUser(7L, "test");
    private final Map<Long, ChunkHitVO> rows = new HashMap<>();
    private List<ChunkHitVO> lexical = List.of();
    private HybridChunkSearch search;

    /** 准备有权限的空间和按当前版本复核的假数据库。 */
    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.toolkit.LambdaUtils.extract(
                (com.baomidou.mybatisplus.core.toolkit.support.SFunction<SpaceMember, ?>) SpaceMember::getSpaceId);
        search = new HybridChunkSearch(null, chunks, keywords, embeddings, vectors, reranker, spaces, members);
        when(spaces.selectById(1L)).thenReturn(new Space());
        when(members.selectOne(any())).thenReturn(new SpaceMember());
        when(chunks.searchChunks(eq(1L), anyString(), eq(20))).thenAnswer(call -> lexical);
        when(chunks.searchChunksInDocument(eq(1L), anyLong(), anyString(), eq(20))).thenAnswer(call -> lexical);
        when(chunks.findReadableChunk(anyLong(), anyLong(), anyLong(), anyInt())).thenAnswer(call -> {
            ChunkHitVO hit = rows.get(call.getArgument(2));

            return hit != null && hit.getSpaceId().equals(call.getArgument(0))
                    && hit.getDocumentId().equals(call.getArgument(1))
                    && hit.getParseVersion().equals(call.getArgument(3)) ? hit : null;
        });
    }

    /** 重排前保留二十个候选，而不是提前截成六条；所有出站候选都记录依赖。 */
    @Test
    void reranksBeforeFinalLimitAndTracksEveryOutboundSource() {
        lexical = IntStream.rangeClosed(1, 20).mapToObj(this::hit).toList();
        when(reranker.enabled()).thenReturn(true);
        when(reranker.rerank(anyString(), anyList(), eq(20)))
                .thenReturn(IntStream.iterate(19, i -> i - 1).limit(20).boxed().toList());

        List<Long> dependencies = new ArrayList<>();

        try (var scope = RetrievalContext.open(Long.MAX_VALUE, () -> { }, h -> dependencies.add(h.getChunkId()))) {
            List<ChunkHitVO> result = search.search(1L, null, "回滚流程", user, 6);

            assertEquals(List.of(20L, 19L, 18L, 17L, 16L, 15L), result.stream().map(ChunkHitVO::getChunkId).toList());
        }

        assertEquals(20, dependencies.size());
        verify(chunks).searchChunks(1L, "\"回滚流程\"", 20);
    }

    /** 向量化接收原始查询，同时出现于两路的片段获得融合优势。 */
    @Test
    void preservesNaturalQueryAndFusesByRank() {
        ChunkHitVO first = hit(1);
        ChunkHitVO shared = hit(2);
        ChunkHitVO third = hit(3);

        lexical = List.of(first, shared);
        when(embeddings.enabled()).thenReturn(true);
        when(vectors.enabled()).thenReturn(true);

        String query = "如何撤销发布 + 回滚";

        when(embeddings.embed(List.of(query))).thenReturn(List.of(List.of(1f, 0f)));
        when(vectors.search(eq(1L), isNull(), anyList(), eq(20))).thenReturn(List.of(candidate(shared), candidate(third)));

        var result = search.search(1L, null, query, user, 6);

        assertEquals(2L, result.get(0).getChunkId());
        assertEquals(3, result.size());
        verify(embeddings).embed(List.of(query));
    }

    /** 单字虽然无法生成 ngram 查询，仍能独立进行语义召回。 */
    @Test
    void singleCharacterCanUseSemanticSearch() {
        ChunkHitVO found = hit(3);

        when(embeddings.enabled()).thenReturn(true);
        when(vectors.enabled()).thenReturn(true);
        when(embeddings.embed(List.of("云"))).thenReturn(List.of(List.of(1f, 0f)));
        when(vectors.search(eq(1L), isNull(), anyList(), eq(20))).thenReturn(List.of(candidate(found)));

        assertEquals(1, search.search(1L, null, "云", user, 6).size());
        verify(chunks, never()).searchChunks(anyLong(), anyString(), anyInt());
    }

    /** 向量服务失败时沿用关键词结果，不把数据库异常当作向量降级。 */
    @Test
    void vectorFailureKeepsKeywordResults() {
        lexical = List.of(hit(1));
        when(embeddings.enabled()).thenReturn(true);
        when(vectors.enabled()).thenReturn(true);
        when(embeddings.embed(anyList())).thenThrow(new RetrievalException("测试超时"));

        assertEquals(1L, search.search(1L, null, "备份", user, 6).get(0).getChunkId());
        verify(vectors, never()).search(anyLong(), any(), anyList(), anyInt());
    }

    /** 重排失败保留融合结果，不能丢弃已记录的出站依赖。 */
    @Test
    void rerankFailureKeepsRankAndDependencies() {
        lexical = List.of(hit(1), hit(2));
        when(reranker.enabled()).thenReturn(true);
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenThrow(new RetrievalException("测试限流"));

        List<Long> dependencies = new ArrayList<>();

        try (var scope = RetrievalContext.open(Long.MAX_VALUE, () -> { }, h -> dependencies.add(h.getChunkId()))) {
            assertEquals(2, search.search(1L, null, "备份", user, 6).size());
        }

        assertEquals(List.of(1L, 2L), dependencies);
    }

    /** 空间或版本伪造的候选不能进入外部重排请求。 */
    @Test
    void verifiesCandidatesBeforeRerankEgress() {
        ChunkHitVO good = hit(1);
        ChunkHitVO other = hit(2);
        other.setSpaceId(9L);
        when(keywords.enabled()).thenReturn(true);
        when(keywords.searchCandidates(1L, 101L, "备份", 20)).thenReturn(List.of(
                candidate(good), candidate(other), new ChunkIndexHit(1L, 101L, 99, null)));
        when(reranker.enabled()).thenReturn(true);
        when(reranker.rerank("备份", List.of("【文档: 测试资料】\n正文1"), 1)).thenReturn(List.of(0));

        assertEquals(1, search.search(1L, 101L, "备份", user, 6).size());
        verify(reranker).rerank("备份", List.of("【文档: 测试资料】\n正文1"), 1);
    }

    /** 外部调用期间成员资格被撤销时，不再发送候选正文。 */
    @Test
    void membershipRevocationIsNotSwallowedAsFallback() {
        lexical = List.of(hit(1));
        when(embeddings.enabled()).thenReturn(true);
        when(vectors.enabled()).thenReturn(true);
        when(reranker.enabled()).thenReturn(true);
        when(embeddings.embed(anyList())).thenAnswer(call -> {
            when(members.selectOne(any())).thenReturn(null);

            return List.of(List.of(1f, 0f));
        });
        when(vectors.search(eq(1L), isNull(), anyList(), eq(20))).thenReturn(List.of());

        assertThrows(BusinessException.class, () -> search.search(1L, null, "备份", user, 6));
        verify(reranker, never()).rerank(anyString(), anyList(), anyInt());
    }

    /** 取消检查异常必须向上传播，不能继续外部调用。 */
    @Test
    void stoppedRunDoesNotFallBackIntoMoreRequests() {
        IllegalStateException cancelled = new IllegalStateException("已取消");

        try (var scope = RetrievalContext.open(Long.MAX_VALUE, () -> { throw cancelled; }, hit -> { })) {
            assertSame(cancelled, assertThrows(IllegalStateException.class,
                    () -> search.search(1L, null, "备份", user, 6)));
        }

        verifyNoInteractions(embeddings, vectors, reranker);
    }

    /** 返回前再核对版本，重排期间失效的正文不进入结果。 */
    @Test
    void sourceDeletedDuringRerankIsRemovedBeforeReturn() {
        lexical = List.of(hit(1), hit(2));
        when(reranker.enabled()).thenReturn(true);
        when(reranker.rerank(anyString(), anyList(), eq(2))).thenAnswer(call -> {
            rows.remove(1L);

            return List.of(0, 1);
        });

        assertEquals(List.of(2L), search.search(1L, null, "备份", user, 6).stream().map(ChunkHitVO::getChunkId).toList());
    }


    /** 词法检索阻塞超时后直接中止，不回退查 MySQL，也不调用重排。 */
    @Test
    void lexicalBlockingExceedingDeadlineAbortsWithoutMysqlFallbackOrRerank() {
        when(keywords.enabled()).thenReturn(true);
        when(keywords.searchCandidates(anyLong(), any(), anyString(), anyInt())).thenAnswer(call -> {
            try {
                Thread.sleep(80);
            } catch (InterruptedException ignored) { }
            return List.of(candidate(hit(1)));
        });

        try (var scope = RetrievalContext.open(System.currentTimeMillis() + 30, () -> { }, hit -> { })) {
            assertThrows(IllegalStateException.class, () -> search.search(1L, null, "流程", user, 6));
        }

        verify(chunks, never()).searchChunks(anyLong(), anyString(), anyInt());
        verify(chunks, never()).searchChunksInDocument(anyLong(), anyLong(), anyString(), anyInt());
        verifyNoInteractions(reranker);
    }

    /** 词法检索耗尽时间预算后，跳过慢向量分支直接返回词法结果。 */
    @Test
    void lexicalConsumingSearchBudgetSkipsSlowSemanticAndReturnsLexical() {
        lexical = List.of(hit(1));
        when(embeddings.enabled()).thenReturn(true);
        when(vectors.enabled()).thenReturn(true);

        when(embeddings.embed(anyList())).thenAnswer(call -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) { }
            return List.of(List.of(1f, 0f));
        });

        when(keywords.enabled()).thenReturn(true);
        when(keywords.searchCandidates(anyLong(), any(), anyString(), anyInt())).thenAnswer(call -> {
            try { Thread.sleep(110); } catch (InterruptedException ignored) { }
            return List.of(candidate(hit(1)));
        });

        long deadline = System.currentTimeMillis() + 190;
        try (var scope = RetrievalContext.open(deadline, () -> { }, hit -> { })) {
            List<ChunkHitVO> results = search.search(1L, null, "流程", user, 6);
            assertEquals(1, results.size());
            assertEquals(1L, results.get(0).getChunkId());
        }
    }

    /** 向量检索阻塞超时后，在预算内降级返回词法结果。 */
    @Test
    void vectorBlockingTimesOutAndDegradesToLexicalWithinBudget() {
        lexical = List.of(hit(1));
        when(embeddings.enabled()).thenReturn(true);
        when(vectors.enabled()).thenReturn(true);

        when(embeddings.embed(anyList())).thenAnswer(call -> {
            try {
                Thread.sleep(1200);
            } catch (InterruptedException ignored) { }
            return List.of(List.of(1f, 0f));
        });

        long deadline = System.currentTimeMillis() + 450;
        long start = System.currentTimeMillis();
        try (var scope = RetrievalContext.open(deadline, () -> { }, hit -> { })) {
            List<ChunkHitVO> results = search.search(1L, null, "流程", user, 6);
            assertEquals(1, results.size());
            assertEquals(1L, results.get(0).getChunkId());
        }
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 800, "Should degrade within budget instead of waiting full vector time: " + elapsed + "ms");
    }

    /** 已取消的向量分支即便 embed 忽略中断，在调用 Milvus 前必须停止。 */
    @Test
    void cancelledVectorBranchStopsBeforeCallingMilvusEvenIfEmbedIgnoresInterrupt() {
        lexical = List.of(hit(1));
        when(embeddings.enabled()).thenReturn(true);
        when(vectors.enabled()).thenReturn(true);

        when(embeddings.embed(anyList())).thenAnswer(call -> {
            long target = System.currentTimeMillis() + 250;
            while (System.currentTimeMillis() < target) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) { }
            }
            return List.of(List.of(1f, 0f));
        });

        long deadline = System.currentTimeMillis() + 100;
        try (var scope = RetrievalContext.open(deadline, () -> { }, hit -> { })) {
            List<ChunkHitVO> results = search.search(1L, null, "流程", user, 6);
            assertEquals(1, results.size());
        }

        try {
            Thread.sleep(250);
        } catch (InterruptedException ignored) { }
        verify(vectors, never()).search(anyLong(), any(), anyList(), anyInt());
    }

    /** 检索线程池饱和被拒绝时，立即降级为词法检索。 */
    @Test
    void saturatedRetrievalExecutorRejectsVectorTaskAndDegradesImmediately() {
        lexical = List.of(hit(1));
        when(embeddings.enabled()).thenReturn(true);
        when(vectors.enabled()).thenReturn(true);

        ExecutorService rejectingExecutor = mock(ExecutorService.class);
        doThrow(new RejectedExecutionException("Pool full")).when(rejectingExecutor).execute(any());

        HybridChunkSearch saturatedSearch = new HybridChunkSearch(
                null, chunks, keywords, embeddings, vectors, reranker, spaces, members,
                new RetrievalProperties(), rejectingExecutor);

        List<ChunkHitVO> results = saturatedSearch.search(1L, null, "流程", user, 6);
        assertEquals(1, results.size());
        assertEquals(1L, results.get(0).getChunkId());
        verify(embeddings, never()).embed(anyList());
    }

    /** 双参数查询：keyword 路由到词法，semanticQuery 路由到向量化和重排。 */
    @Test
    void dualParameterRoutesKeywordToLexicalAndSemanticQueryToMilvusAndRerank() {
        lexical = List.of(hit(1));
        when(embeddings.enabled()).thenReturn(true);
        when(vectors.enabled()).thenReturn(true);
        when(reranker.enabled()).thenReturn(true);

        when(embeddings.embed(List.of("如何执行系统回滚操作？"))).thenReturn(List.of(List.of(1f, 0f)));
        when(vectors.search(eq(1L), isNull(), anyList(), eq(20))).thenReturn(List.of(candidate(hit(1))));
        when(reranker.rerank(eq("如何执行系统回滚操作？"), anyList(), anyInt())).thenReturn(List.of(0));

        var results = search.search(1L, null, "回滚流程", "如何执行系统回滚操作？", user, 6);
        assertEquals(1, results.size());

        verify(chunks).searchChunks(1L, "\"回滚流程\"", 20);
        verify(embeddings).embed(List.of("如何执行系统回滚操作？"));
        verify(reranker).rerank(eq("如何执行系统回滚操作？"), anyList(), eq(1));
    }

    /** 文档多样性门控：单篇文档最多 2 个分块，除非其余配额无法填满。 */
    @Test
    void diversityGateLimitsChunksPerDocUnlessRemainingSlotsCannotBeFilled() {
        ChunkHitVO d1_1 = hit(1, 101L);
        ChunkHitVO d1_2 = hit(2, 101L);
        ChunkHitVO d1_3 = hit(3, 101L);
        ChunkHitVO d2_1 = hit(4, 102L);
        ChunkHitVO d2_2 = hit(5, 102L);
        ChunkHitVO d3_1 = hit(6, 103L);

        List<ChunkHitVO> candidates = List.of(d1_1, d1_2, d1_3, d2_1, d2_2, d3_1);

        List<ChunkHitVO> selected = HybridChunkSearch.applyDiversity(candidates, 5, 2);
        assertEquals(List.of(1L, 2L, 4L, 5L, 6L), selected.stream().map(ChunkHitVO::getChunkId).toList());

        List<ChunkHitVO> allSix = HybridChunkSearch.applyDiversity(candidates, 6, 2);
        assertEquals(List.of(1L, 2L, 4L, 5L, 6L, 3L), allSix.stream().map(ChunkHitVO::getChunkId).toList());
    }

    /** Small-to-Big 上下文扩展：在独立预算内扩展相邻块且严格去重 overlap，不改变原始引用 ID。 */
    @Test
    void smallToBigExpandsAdjacentChunksWithoutCrossingHeadingsAndDeduplicatesOverlap() {
        ChunkHitVO primary = hit(2, 101L);
        primary.setChunkIndex(1);
        primary.setHeading("第一章 概述 > 1.1 背景");
        primary.setCharStart(100);
        primary.setCharEnd(150);
        primary.setExcerpt("这是中间正文内容。");

        ChunkHitVO prev = hit(1, 101L);
        prev.setChunkIndex(0);
        prev.setHeading("第一章 概述 > 1.1 背景");
        prev.setExcerpt("前文背景介绍，这是中间");

        ChunkHitVO next = hit(3, 101L);
        next.setChunkIndex(2);
        next.setHeading("第一章 概述 > 1.1 背景");
        next.setExcerpt("正文内容。后续补充说明");

        when(chunks.findReadableChunkByIndex(1L, 101L, 0, 1)).thenReturn(prev);
        when(chunks.findReadableChunkByIndex(1L, 101L, 2, 1)).thenReturn(next);

        lexical = List.of(primary);
        var results = search.search(1L, null, "中间正文", user, 6);

        assertEquals(1, results.size());
        ChunkHitVO hit = results.get(0);
        assertEquals(2L, hit.getChunkId());
        assertEquals("第一章 概述 > 1.1 背景", hit.getHeading());
        assertEquals("前文背景介绍，这是中间正文内容。后续补充说明", hit.getExcerpt());
    }

    /** Small-to-Big 不跨越不同章节。 */
    @Test
    void smallToBigDoesNotExpandAcrossDifferentHeadings() {
        ChunkHitVO primary = hit(2, 101L);
        primary.setChunkIndex(1);
        primary.setHeading("第二章 架构");
        primary.setExcerpt("架构设计正文。");

        ChunkHitVO prev = hit(1, 101L);
        prev.setChunkIndex(0);
        prev.setHeading("第一章 概述");
        prev.setExcerpt("前一章节概述内容。");

        when(chunks.findReadableChunkByIndex(1L, 101L, 0, 1)).thenReturn(prev);
        when(chunks.findReadableChunkByIndex(1L, 101L, 2, 1)).thenReturn(null);

        lexical = List.of(primary);
        var results = search.search(1L, null, "架构设计", user, 6);

        assertEquals(1, results.size());
        assertEquals("架构设计正文。", results.get(0).getExcerpt());
    }

    /** 未完成切换的新代次向量被过滤，避免引发 SOURCE_CHANGED 崩溃，并由词法检索兜底。 */
    @Test
    void unswitchedNewGenerationVectorsAreFilteredOutWithoutSourceChangedError() {
        VectorIndexMapper vectorIndexMapper = mock(VectorIndexMapper.class);
        HybridChunkSearch searchWithMapper = new HybridChunkSearch(null, chunks, keywords, embeddings, vectors, reranker, spaces, members, new RetrievalProperties(), null, vectorIndexMapper);

        VectorIndexMapper.Task task = new VectorIndexMapper.Task();
        task.setDocumentId(101L);
        task.setGeneration(2L);
        task.setActiveGeneration(1L);
        task.setActiveParseVersion(1);
        when(vectorIndexMapper.task(101L)).thenReturn(task);

        ChunkIndexHit newCandidate = new ChunkIndexHit(200L, 101L, 2, null);
        ChunkHitVO resolved = searchWithMapper.resolveVectorCandidate(1L, 101L, newCandidate);
        assertNull(resolved, "未完成切换的新代次向量应被 resolveVectorCandidate 过滤");

        when(embeddings.enabled()).thenReturn(true);
        when(vectors.enabled()).thenReturn(true);
        when(embeddings.embed(anyList())).thenReturn(List.of(List.of(1f, 0f)));
        when(vectors.search(eq(1L), any(), anyList(), anyInt())).thenReturn(List.of(newCandidate));
        ChunkHitVO lexicalHit = hit(100, 101L);
        lexical = List.of(lexicalHit);

        List<ChunkHitVO> results = searchWithMapper.search(1L, 101L, "关键字", "自然语言查询", user, 6);
        assertEquals(1, results.size());
        assertEquals(100L, results.get(0).getChunkId());
    }

    /** 同版本重建切换前，旧向量保持可检索。 */
    @Test
    void sameVersionRebuildPreservesSearchableOldVectorsBeforeSwitch() {
        VectorIndexMapper vectorIndexMapper = mock(VectorIndexMapper.class);
        HybridChunkSearch searchWithMapper = new HybridChunkSearch(null, chunks, keywords, embeddings, vectors, reranker, spaces, members, new RetrievalProperties(), null, vectorIndexMapper);

        VectorIndexMapper.Task task = new VectorIndexMapper.Task();
        task.setDocumentId(101L);
        task.setGeneration(2L);
        task.setActiveGeneration(1L);
        task.setActiveParseVersion(1);
        when(vectorIndexMapper.task(101L)).thenReturn(task);

        ChunkHitVO oldChunk = hit(1, 101L);
        oldChunk.setParseVersion(1);
        ChunkIndexHit oldCandidate = candidate(oldChunk);

        ChunkHitVO resolved = searchWithMapper.resolveVectorCandidate(1L, 101L, oldCandidate);
        assertNotNull(resolved, "同版本重建切换前，旧向量应保持可正常检索");
        assertEquals(1L, resolved.getChunkId());
    }

    /** 切换完成后，词法与语义检索统一使用新版本。 */
    @Test
    void lexicalAndSemanticSearchUseConsistentParseVersionAfterSwitch() {
        VectorIndexMapper vectorIndexMapper = mock(VectorIndexMapper.class);
        HybridChunkSearch searchWithMapper = new HybridChunkSearch(null, chunks, keywords, embeddings, vectors, reranker, spaces, members, new RetrievalProperties(), null, vectorIndexMapper);

        VectorIndexMapper.Task task = new VectorIndexMapper.Task();
        task.setDocumentId(101L);
        task.setGeneration(2L);
        task.setActiveGeneration(2L);
        task.setActiveParseVersion(2);
        when(vectorIndexMapper.task(101L)).thenReturn(task);

        ChunkIndexHit staleCandidate = new ChunkIndexHit(1L, 101L, 1, null);
        assertNull(searchWithMapper.resolveVectorCandidate(1L, 101L, staleCandidate));

        ChunkHitVO v2Chunk = hit(200, 101L);
        v2Chunk.setParseVersion(2);
        rows.put(200L, v2Chunk);
        ChunkIndexHit newCandidate = candidate(v2Chunk);

        ChunkHitVO resolved = searchWithMapper.resolveVectorCandidate(1L, 101L, newCandidate);
        assertNotNull(resolved);
        assertEquals(2, resolved.getParseVersion());
    }

    /** 语义分支命中时，依赖不在异步线程修改，而由主线程在确认未取消后完整合并记录。 */
    @Test
    void semanticHitsRecordCompleteDependenciesOnMainThreadWithoutAsyncMutation() {
        ChunkHitVO semOnly = hit(50, 102L);
        ChunkHitVO lexOnly = hit(10, 101L);
        lexical = List.of(lexOnly);

        when(embeddings.enabled()).thenReturn(true);
        when(vectors.enabled()).thenReturn(true);
        when(embeddings.embed(anyList())).thenReturn(List.of(List.of(1f, 0f)));
        when(vectors.search(eq(1L), isNull(), anyList(), eq(20))).thenReturn(List.of(candidate(semOnly)));

        Thread mainThread = Thread.currentThread();
        List<Long> recordedChunkIds = new ArrayList<>();
        List<Thread> recordingThreads = new ArrayList<>();

        try (var scope = RetrievalContext.open(Long.MAX_VALUE, () -> { }, h -> {
            recordingThreads.add(Thread.currentThread());
            recordedChunkIds.add(h.getChunkId());
        })) {
            // 即使异步子线程尝试调用 RetrievalContext.record，也会被拦截禁止
            var wrapped = RetrievalContext.wrap(() -> {
                assertThrows(IllegalStateException.class, () -> RetrievalContext.record(semOnly));
                return "ok";
            }, () -> false);
            assertEquals("ok", wrapped.get());

            List<ChunkHitVO> results = search.search(1L, null, "备份策略", "对比备份策略差异", user, 6);
            assertEquals(2, results.size());
        }

        // 语义命中块 50L 的依赖已在主线程被完整记录，且记录线程全部为主线程
        assertTrue(recordedChunkIds.contains(50L));
        assertTrue(recordedChunkIds.contains(10L) || recordedChunkIds.equals(List.of(50L)));
        assertTrue(recordingThreads.stream().allMatch(t -> t == mainThread));
    }

    /**
     * 版本切换窗口期（V1 -> V2）四项保障验证：
     * 1. 不返回旧版（V1）脏引用；
     * 2. 不触发 SOURCE_CHANGED 崩溃；
     * 3. 向量暂不可用时词法 V2 正常兜底，不误判为无相关文档；
     * 4. 新蓝绿集合切换完成后立即恢复 V2 混合检索。
     */
    @Test
    void versionSwitchWindowGuaranteesNoDirtyCitationsNoSourceChangedLexicalFallbackAndRecoveryAfterSwitch() {
        VectorIndexMapper vectorIndexMapper = mock(VectorIndexMapper.class);
        HybridChunkSearch searchWithMapper = new HybridChunkSearch(
                null, chunks, keywords, embeddings, vectors, reranker, spaces, members,
                new RetrievalProperties(), null, vectorIndexMapper);

        // 文档 101 已完成重解析进入 V2：document_content 中旧 V1 分块(chunkId=1)已移除，新 V2 分块(chunkId=201,202)已就绪
        rows.clear();
        ChunkHitVO v2LexChunk = hit(201, 101L);
        v2LexChunk.setParseVersion(2);
        v2LexChunk.setExcerpt("V2 词法分块内容");
        rows.put(201L, v2LexChunk);

        ChunkHitVO v2SemChunk = hit(202, 101L);
        v2SemChunk.setParseVersion(2);
        v2SemChunk.setExcerpt("V2 语义独有分块内容");
        rows.put(202L, v2SemChunk);

        lexical = List.of(v2LexChunk);

        // 阶段 A：向量重建尚在进行中，MySQL 中 active_generation=1, active_parse_version=1, active_collection="teamdocs_chunks_d101_g1"
        VectorIndexMapper.Task taskDuringRebuild = new VectorIndexMapper.Task();
        taskDuringRebuild.setDocumentId(101L);
        taskDuringRebuild.setGeneration(2L);
        taskDuringRebuild.setTargetParseVersion(2);
        taskDuringRebuild.setActiveGeneration(1L);
        taskDuringRebuild.setActiveParseVersion(1);
        taskDuringRebuild.setActiveCollection("teamdocs_chunks_d101_g1");
        when(vectorIndexMapper.task(101L)).thenReturn(taskDuringRebuild);

        when(embeddings.enabled()).thenReturn(true);
        when(vectors.enabled()).thenReturn(true);
        when(reranker.enabled()).thenReturn(true);
        when(embeddings.embed(anyList())).thenReturn(List.of(List.of(1f, 0f)));
        // 旧集合返回 V1 候选(chunkId=1, parseVersion=1)
        ChunkIndexHit staleV1Candidate = new ChunkIndexHit(1L, 101L, 1, null);
        when(vectors.search(eq("teamdocs_chunks_d101_g1"), eq(1L), eq(101L), anyList(), eq(20)))
                .thenReturn(List.of(staleV1Candidate));
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenAnswer(call -> {
            List<?> list = call.getArgument(1);
            return IntStream.range(0, list.size()).boxed().toList();
        });

        List<ChunkHitVO> recordedDuringWindow = new ArrayList<>();
        try (var scope = RetrievalContext.open(Long.MAX_VALUE, () -> { }, h -> {
            // 模拟 AgentTools.recordRetrievalDependency：若收到非 V2 分块则立即抛 SOURCE_CHANGED
            if (!Integer.valueOf(2).equals(h.getParseVersion())) {
                throw new IllegalStateException("SOURCE_CHANGED");
            }
            recordedDuringWindow.add(h);
        })) {
            List<ChunkHitVO> windowHits = searchWithMapper.search(1L, 101L, "分块内容", "查询 V2 分块内容", user, 6);
            // 1) 不为空（不误判为无相关文档），返回 V2 词法结果
            assertEquals(1, windowHits.size());
            assertEquals(201L, windowHits.get(0).getChunkId());
            // 2) 绝不返回 V1 旧引用
            assertTrue(windowHits.stream().allMatch(h -> Integer.valueOf(2).equals(h.getParseVersion())));
        }
        assertEquals(1, recordedDuringWindow.size());
        assertEquals(201L, recordedDuringWindow.get(0).getChunkId());

        // 阶段 B：新蓝绿集合构建完成并原子切换为 active_generation=2, active_parse_version=2, active_collection="teamdocs_chunks_d101_g2"
        VectorIndexMapper.Task taskAfterSwitch = new VectorIndexMapper.Task();
        taskAfterSwitch.setDocumentId(101L);
        taskAfterSwitch.setGeneration(2L);
        taskAfterSwitch.setTargetParseVersion(2);
        taskAfterSwitch.setActiveGeneration(2L);
        taskAfterSwitch.setActiveParseVersion(2);
        taskAfterSwitch.setActiveCollection("teamdocs_chunks_d101_g2");
        when(vectorIndexMapper.task(101L)).thenReturn(taskAfterSwitch);
        when(vectors.search(eq("teamdocs_chunks_d101_g2"), eq(1L), eq(101L), anyList(), eq(20)))
                .thenReturn(List.of(candidate(v2SemChunk)));

        List<ChunkHitVO> postSwitchHits = searchWithMapper.search(1L, 101L, "分块内容", "查询 V2 分块内容", user, 6);
        assertEquals(2, postSwitchHits.size());
        assertEquals(List.of(201L, 202L), postSwitchHits.stream().map(ChunkHitVO::getChunkId).sorted().toList());
    }

    /** JDBC 查询超时或剩余预算 <= 0 时立即失败中止，不吞掉超时异常。 */
    @Test
    void jdbcTimeoutAndZeroBudgetAbortImmediately() {
        // 1. budget <= 0 时 RetrievalContext.timeoutMillis 立即抛 IllegalStateException
        try (var expired = RetrievalContext.open(System.currentTimeMillis() - 10, () -> { }, h -> { })) {
            assertThrows(IllegalStateException.class, () -> RetrievalContext.timeoutMillis(1000));
        }

        // 2. JDBC 查询抛出 SQLTimeoutException 包装异常时，executeJdbc 转换为检索运行已超时
        when(chunks.searchChunks(eq(1L), anyString(), eq(20))).thenAnswer(call -> {
            throw new RuntimeException("MyBatis query timeout", new java.sql.SQLTimeoutException("Statement cancelled due to timeout"));
        });

        try (var scope = RetrievalContext.open(System.currentTimeMillis() + 5000, () -> { }, h -> { })) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> search.search(1L, null, "备份策略", user, 6));
            assertTrue(ex.getMessage().contains("超时"));
        }
    }

    /** 构造指定文档的测试分块。 */
    private ChunkHitVO hit(int id, Long docId) {
        ChunkHitVO hit = new ChunkHitVO();
        hit.setChunkId((long) id);
        hit.setDocumentId(docId);
        hit.setSpaceId(1L);
        hit.setParseVersion(1);
        hit.setExcerpt("正文" + id);
        hit.setDocumentName(docId != null && !docId.equals(101L) ? "测试资料" + docId : "测试资料");

        rows.put((long) id, hit);

        return hit;
    }

    /** 构造当前空间的测试分块。 */
    private ChunkHitVO hit(int id) {
        return hit(id, 101L);
    }

    /** 将分块转换为索引返回的候选标识。 */
    private ChunkIndexHit candidate(ChunkHitVO hit) {
        return new ChunkIndexHit(hit.getChunkId(), hit.getDocumentId(), hit.getParseVersion(), null);
    }
}
