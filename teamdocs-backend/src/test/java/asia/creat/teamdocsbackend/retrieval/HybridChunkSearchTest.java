package asia.creat.teamdocsbackend.retrieval;

import asia.creat.common.exception.BusinessException;
import asia.creat.entity.Space;
import asia.creat.entity.SpaceMember;
import asia.creat.mapper.DocumentContentMapper;
import asia.creat.mapper.SpaceMapper;
import asia.creat.mapper.SpaceMemberMapper;
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
        when(reranker.rerank("备份", List.of("正文1"), 1)).thenReturn(List.of(0));

        assertEquals(1, search.search(1L, 101L, "备份", user, 6).size());
        verify(reranker).rerank("备份", List.of("正文1"), 1);
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

    /** 构造当前空间的测试分块。 */
    private ChunkHitVO hit(int id) {
        ChunkHitVO hit = new ChunkHitVO();
        hit.setChunkId((long) id);
        hit.setDocumentId(101L);
        hit.setSpaceId(1L);
        hit.setParseVersion(1);
        hit.setExcerpt("正文" + id);
        hit.setDocumentName("测试资料");

        rows.put((long) id, hit);

        return hit;
    }

    /** 将分块转换为索引返回的候选标识。 */
    private ChunkIndexHit candidate(ChunkHitVO hit) {
        return new ChunkIndexHit(hit.getChunkId(), hit.getDocumentId(), hit.getParseVersion(), null);
    }
}
