package asia.creat.teamdocsbackend.service.impl;

import asia.creat.mapper.DocumentMapper;
import asia.creat.service.impl.RecentDocumentServiceImpl;
import asia.creat.utils.CacheClient;
import asia.creat.vo.RecentDocumentVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static asia.creat.utils.RedisConstants.MAX_RECENT_DOCUMENTS;
import static asia.creat.utils.RedisConstants.RECENT_DOCUMENT_PREFIX;
import static asia.creat.utils.RedisConstants.RECENT_DOCUMENT_TTL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecentDocumentServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final String KEY = RECENT_DOCUMENT_PREFIX + USER_ID;

    @Mock
    private CacheClient cacheClient;

    @Mock
    private DocumentMapper documentMapper;

    private RecentDocumentServiceImpl recentDocumentService;

    @BeforeEach
    void setUp() {
        recentDocumentService = new RecentDocumentServiceImpl(cacheClient, documentMapper);
    }

    // 记录时写入 ZSet，并裁剪到最多 20 条
    @Test
    void recordShouldAddZSetAndTrimToMax() {
        Long documentId = 10L;

        recentDocumentService.recordRecentDocument(USER_ID, documentId);

        verify(cacheClient).addZSet(
                eq(KEY),
                eq("10"),
                anyDouble(),
                eq(RECENT_DOCUMENT_TTL)
        );
        verify(cacheClient).removeZSetRangeByRank(
                KEY,
                0,
                -(MAX_RECENT_DOCUMENTS + 1)
        );
    }

    // Redis 无数据时直接返回空列表，不查库
    @Test
    void getShouldReturnEmptyWhenZSetEmpty() {
        when(cacheClient.getZSetReverseRangeWithScores(KEY, 0, MAX_RECENT_DOCUMENTS - 1))
                .thenReturn(Collections.emptySet());

        List<RecentDocumentVO> result = recentDocumentService.getRecentDocuments(USER_ID);

        assertTrue(result.isEmpty());
        verify(documentMapper, never()).listAccessibleRecentDocuments(any(), any());
        verify(cacheClient, never()).removeZSetMembers(anyString(), any());
    }

    // 返回顺序与 Redis 一致，并填充 lastViewedAt
    @Test
    void getShouldRestoreRedisOrderAndLastViewedAt() {
        long newer = 2_000L;
        long older = 1_000L;
        // reverseRange：先新后旧
        Set<TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(new DefaultTypedTuple<>("20", (double) newer));
        tuples.add(new DefaultTypedTuple<>("10", (double) older));

        when(cacheClient.getZSetReverseRangeWithScores(KEY, 0, MAX_RECENT_DOCUMENTS - 1))
                .thenReturn(tuples);

        RecentDocumentVO doc10 = new RecentDocumentVO();
        doc10.setDocumentId(10L);
        doc10.setName("old");
        RecentDocumentVO doc20 = new RecentDocumentVO();
        doc20.setDocumentId(20L);
        doc20.setName("new");
        // 库返回故意打乱顺序
        when(documentMapper.listAccessibleRecentDocuments(eq(USER_ID), eq(List.of(20L, 10L))))
                .thenReturn(List.of(doc10, doc20));

        List<RecentDocumentVO> result = recentDocumentService.getRecentDocuments(USER_ID);

        assertEquals(2, result.size());
        assertEquals(20L, result.get(0).getDocumentId());
        assertEquals(10L, result.get(1).getDocumentId());
        assertEquals(newer, toEpochMilli(result.get(0).getLastViewedAt()));
        assertEquals(older, toEpochMilli(result.get(1).getLastViewedAt()));
        verify(cacheClient, never()).removeZSetMembers(anyString(), any());
    }

    // 库中不存在（已删/无权限）的 member 应被清理
    @Test
    void getShouldRemoveInaccessibleMembers() {
        Set<TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(new DefaultTypedTuple<>("20", 2_000.0));
        tuples.add(new DefaultTypedTuple<>("10", 1_000.0));

        when(cacheClient.getZSetReverseRangeWithScores(KEY, 0, MAX_RECENT_DOCUMENTS - 1))
                .thenReturn(tuples);

        RecentDocumentVO doc20 = new RecentDocumentVO();
        doc20.setDocumentId(20L);
        when(documentMapper.listAccessibleRecentDocuments(eq(USER_ID), eq(List.of(20L, 10L))))
                .thenReturn(List.of(doc20));

        List<RecentDocumentVO> result = recentDocumentService.getRecentDocuments(USER_ID);

        assertEquals(1, result.size());
        assertEquals(20L, result.get(0).getDocumentId());
        verify(cacheClient).removeZSetMembers(KEY, "10");
    }

    private long toEpochMilli(java.time.LocalDateTime time) {
        return time.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
