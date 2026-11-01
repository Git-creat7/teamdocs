package asia.creat.teamdocsbackend.retrieval;

import asia.creat.config.EmbeddingProperties;
import asia.creat.config.MilvusProperties;
import asia.creat.mapper.DocumentContentMapper;
import asia.creat.mapper.VectorIndexMapper;
import asia.creat.retrieval.MilvusVectorClient;
import asia.creat.retrieval.RetrievalException;
import asia.creat.retrieval.SiliconFlowEmbeddingClient;
import asia.creat.retrieval.VectorIndexQueue;
import asia.creat.retrieval.VectorIndexWorker;
import asia.creat.vo.ChunkHitVO;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VectorIndexWorkerTest {
    private final VectorIndexMapper mapper = mock(VectorIndexMapper.class);
    private final DocumentContentMapper chunks = mock(DocumentContentMapper.class);
    private final SiliconFlowEmbeddingClient embeddings = mock(SiliconFlowEmbeddingClient.class);
    private final MilvusVectorClient vectors = mock(MilvusVectorClient.class);
    private final EmbeddingProperties properties = new EmbeddingProperties();
    private final MilvusProperties milvus = new MilvusProperties();
    private final VectorIndexQueue queue = new VectorIndexQueue(mapper, properties, milvus);
    private final VectorIndexWorker worker = new VectorIndexWorker(mapper, chunks, queue, embeddings, vectors);
    private VectorIndexMapper.Task task;
    private VectorIndexMapper.Snapshot snapshot;

    /** 构造已领取前的持久待办，所有外部资源均为 Mock。 */
    @BeforeEach
    void setUp() {
        properties.setApiKey("test-key");
        properties.setModelName("Qwen/Qwen3-Embedding-8B");
        snapshot = new VectorIndexMapper.Snapshot();
        snapshot.setDocumentId(10L);
        snapshot.setParseVersion(1);
        snapshot.setReady(true);
        task = new VectorIndexMapper.Task();
        task.setDocumentId(10L);
        task.setGeneration(1);
        task.setTargetParseVersion(1);
        task.setState("PENDING");
        task.setTargetSignature(queue.signature(snapshot));
        when(mapper.snapshot(10L)).thenReturn(snapshot);
        when(mapper.next(anyLong())).thenReturn(task);
        when(mapper.task(10L)).thenReturn(task);
        when(mapper.reserve(eq(10L), anyLong(), anyString(), anyLong(), anyLong())).thenReturn(true);
        when(mapper.reserve(eq(10L), anyLong(), anyLong(), anyLong())).thenReturn(true);
        when(mapper.verifyCurrent(eq(10L), anyLong(), anyString(), anyLong())).thenReturn(true);
        when(mapper.completeAndSwitch(eq(10L), anyLong(), anyString(), anyLong(), any(), any(), anyInt())).thenReturn(true);
        when(mapper.completeAndSwitch(eq(10L), anyLong(), anyString(), anyLong(), any(), anyInt())).thenReturn(true);
        when(embeddings.enabled()).thenReturn(true);
        when(vectors.enabled()).thenReturn(true);
        when(vectors.defaultCollection()).thenReturn("teamdocs_chunks");
        when(vectors.collectionName(anyLong(), anyLong()))
                .thenAnswer(call -> "teamdocs_chunks_d" + call.getArgument(0) + "_g" + call.getArgument(1));
    }

    /** 缺少向量化授权时不领取任务，也不访问 Milvus。 */
    @Test
    void disabledEmbeddingDoesNotConsumeAttempts() {
        when(embeddings.enabled()).thenReturn(false);
        worker.processNext();
        verifyNoInteractions(mapper, chunks);
        verify(vectors, never()).ensureCollection(anyString());
    }

    /** 先持久化尝试，写入独立蓝绿集合，验证数量、结构与抽样查询并切换有效集合后，再清理旧向量。 */
    @Test
    void persistsAttemptBeforeAnyRemoteMutation() {
        ChunkHitVO chunk = chunk();

        when(chunks.listIndexableDocumentChunks(10L)).thenReturn(List.of(chunk));
        when(embeddings.embed(List.of("合成正文"))).thenReturn(List.of(List.of(1f, 0f)));
        worker.processNext();

        var order = inOrder(mapper, vectors, embeddings);

        order.verify(mapper).reserve(eq(10L), eq(1L), anyString(), anyLong(), anyLong());
        order.verify(vectors).ensureCollection("teamdocs_chunks_d10_g1");
        order.verify(embeddings).embed(List.of("合成正文"));
        order.verify(vectors).upsert("teamdocs_chunks_d10_g1", List.of(chunk), List.of(List.of(1f, 0f)));
        order.verify(vectors).verifyCollection("teamdocs_chunks_d10_g1", 1L, 10L, List.of(1f, 0f));
        order.verify(mapper).completeAndSwitch(eq(10L), eq(1L), anyString(), anyLong(), eq(1), eq("teamdocs_chunks_d10_g1"), eq(1));
        order.verify(vectors).deleteStaleChunks(10L, 1);
        order.verify(mapper).clearCleanupPending(10L, 1L);
        verify(vectors, never()).deleteDocument(10L);
        verify(mapper, never()).fail(anyLong(), anyLong(), anyString(), anyLong(), anyLong());
    }

    /** 删除任务只清理向量并切换为空集合，不调用外部 Embedding。 */
    @Test
    void deletedDocumentDoesNotSendText() {
        when(mapper.snapshot(10L)).thenReturn(null);
        task.setTargetSignature("deleted");
        task.setTargetParseVersion(null);
        task.setActiveCollection("teamdocs_chunks_d10_g1");
        when(chunks.listIndexableDocumentChunks(10L)).thenReturn(List.of());
        worker.processNext();
        verify(embeddings, never()).embed(anyList());
        verify(mapper).completeAndSwitch(eq(10L), eq(1L), anyString(), anyLong(), isNull(), isNull(), eq(0));
        verify(vectors).dropCollection("teamdocs_chunks_d10_g1");
    }

    /** 向量化期间文档版本变化时，不允许迟到结果继续写入，并清理未切换的临时集合。 */
    @Test
    void changedVersionPreventsLatePublication() {
        when(chunks.listIndexableDocumentChunks(10L)).thenReturn(List.of(chunk()));
        when(embeddings.embed(anyList())).thenAnswer(call -> {
            snapshot.setParseVersion(2);

            return List.of(List.of(1f, 0f));
        });
        worker.processNext();
        verify(vectors, never()).upsert(anyString(), anyList(), anyList());
        verify(mapper, never()).completeAndSwitch(anyLong(), anyLong(), anyString(), anyLong(), any(), any(), anyInt());
        verify(vectors).dropCollection("teamdocs_chunks_d10_g1");
        verify(mapper).enqueue(10L, queue.signature(snapshot));
    }

    /** 新代次替换旧任务时，旧任务不能完成新待办。 */
    @Test
    void newerGenerationSurvivesOldWorker() {
        when(chunks.listIndexableDocumentChunks(10L)).thenReturn(List.of(chunk()));
        when(embeddings.embed(anyList())).thenAnswer(call -> {
            when(mapper.verifyCurrent(eq(10L), eq(1L), anyString(), anyLong())).thenReturn(false);

            return List.of(List.of(1f, 0f));
        });
        worker.processNext();
        verify(vectors, never()).upsert(anyString(), anyList(), anyList());
        verify(mapper, never()).completeAndSwitch(anyLong(), anyLong(), anyString(), anyLong(), any(), any(), anyInt());
        verify(mapper).fail(eq(10L), eq(1L), anyString(), anyLong(), anyLong());
    }

    /** 远端失败只记录向量任务失败，不改变正文生命周期。 */
    @Test
    void remoteFailureIsRecordedWithoutPublishingSuccess() {
        when(chunks.listIndexableDocumentChunks(10L)).thenReturn(List.of(chunk()));
        doThrow(new RetrievalException("测试故障")).when(vectors).ensureCollection(anyString());
        worker.processNext();
        verify(mapper).fail(eq(10L), eq(1L), anyString(), anyLong(), anyLong());
        verify(mapper, never()).completeAndSwitch(anyLong(), anyLong(), anyString(), anyLong(), any(), any(), anyInt());
        verify(chunks).listIndexableDocumentChunks(10L);
        verifyNoMoreInteractions(chunks);
    }

    /** 领取竞争失败时不发生任何远端调用。 */
    @Test
    void reservationFailureDoesNotRunTask() {
        when(mapper.reserve(eq(10L), eq(1L), anyString(), anyLong(), anyLong())).thenReturn(false);
        worker.processNext();
        verify(vectors, never()).ensureCollection(anyString());
        verify(embeddings, never()).embed(anyList());
    }

    /** 补扫既包含当前文档，也包含曾建立向量但已彻底删除的文档，并重试清理挂起的旧集合。 */
    @Test
    void reconciliationRepairsMissingEventsAndPurges() {
        when(mapper.documentIds(0)).thenReturn(List.of(10L));
        when(mapper.deletedDocumentIds()).thenReturn(List.of(11L));
        VectorIndexMapper.Task pending = new VectorIndexMapper.Task();
        pending.setDocumentId(12L);
        pending.setActiveGeneration(3L);
        pending.setActiveParseVersion(2);
        pending.setActiveCollection("teamdocs_chunks_d12_g3");
        pending.setStaleCollection("teamdocs_chunks_d12_g2");
        when(mapper.pendingCleanupTasks()).thenReturn(List.of(pending));

        worker.reconcile();

        verify(mapper).enqueue(10L, queue.signature(snapshot));
        verify(mapper).enqueue(11L, "deleted");
        verify(vectors).dropCollection("teamdocs_chunks_d12_g2");
        verify(mapper).clearCleanupPending(12L, 3L);
    }

    /** 集合切换必须产生新目标，否则已有 DONE 任务不会重建向量。 */
    @Test
    void collectionChangeInvalidatesCompletedTarget() {
        String before = queue.signature(snapshot);

        milvus.setCollection("new_vector_collection");
        Assertions.assertNotEquals(before, queue.signature(snapshot));
    }

    /** 验证只有在完全写入、verifyCollection 校验通过且 completeAndSwitch 成功后才删除旧集合。 */
    @Test
    void cleansStaleVectorsOnlyAfterSuccessfulSwitch() {
        task.setGeneration(2L);
        task.setActiveGeneration(1L);
        task.setActiveCollection("teamdocs_chunks_d10_g1");
        ChunkHitVO chunk = chunk();
        when(chunks.listIndexableDocumentChunks(10L)).thenReturn(List.of(chunk));
        when(embeddings.embed(anyList())).thenReturn(List.of(List.of(1f, 0f)));
        when(mapper.completeAndSwitch(eq(10L), eq(2L), anyString(), anyLong(), eq(1), eq("teamdocs_chunks_d10_g2"), eq(1))).thenReturn(true);

        worker.processNext();

        var inOrder = inOrder(mapper, vectors);
        inOrder.verify(vectors).ensureCollection("teamdocs_chunks_d10_g2");
        inOrder.verify(vectors).upsert(eq("teamdocs_chunks_d10_g2"), anyList(), anyList());
        inOrder.verify(vectors).verifyCollection("teamdocs_chunks_d10_g2", 1L, 10L, List.of(1f, 0f));
        inOrder.verify(mapper).completeAndSwitch(eq(10L), eq(2L), anyString(), anyLong(), eq(1), eq("teamdocs_chunks_d10_g2"), eq(1));
        inOrder.verify(vectors).dropCollection("teamdocs_chunks_d10_g1");
        inOrder.verify(mapper).clearCleanupPending(10L, 2L);
    }

    /** 多批次写入同一 chunk_id 的新代次向量时，若第 2 批失败，仅清理新集合，旧集合与旧索引完整保留。 */
    @Test
    void partialBatchFailureDoesNotDeleteExistingVectors() {
        task.setGeneration(2L);
        task.setActiveGeneration(1L);
        task.setActiveParseVersion(1);
        task.setActiveCollection("teamdocs_chunks_d10_g1");

        List<ChunkHitVO> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ChunkHitVO c = chunk();
            c.setChunkId(100L + i);
            c.setChunkIndex(i);
            rows.add(c);
        }
        when(chunks.listIndexableDocumentChunks(10L)).thenReturn(rows);
        when(embeddings.embed(anyList()))
                .thenReturn(java.util.Collections.nCopies(16, List.of(1f, 0f))) // batch 1 (16 chunks)
                .thenThrow(new RetrievalException("网络闪断")); // batch 2 fails

        worker.processNext();

        // 第 1 批只写入新集合 g2，绝不覆盖旧集合 g1 中的相同 chunk_id
        verify(vectors, times(1)).upsert(eq("teamdocs_chunks_d10_g2"), anyList(), anyList());
        verify(vectors, never()).upsert(eq("teamdocs_chunks_d10_g1"), anyList(), anyList());
        // 失败回滚时清理半成品新集合 g2，绝不删除旧集合 g1
        verify(vectors).dropCollection("teamdocs_chunks_d10_g2");
        verify(vectors, never()).dropCollection("teamdocs_chunks_d10_g1");
        verify(vectors, never()).deleteDocument(anyLong());
        verify(mapper, never()).completeAndSwitch(anyLong(), anyLong(), anyString(), anyLong(), any(), any(), anyInt());
        verify(mapper).fail(eq(10L), eq(2L), anyString(), anyLong(), anyLong());
    }

    /** 切换成功后若延迟清理旧集合失败，保留 cleanup_pending 状态并在 reconcile 时成功恢复清理。 */
    @Test
    void cleanupFailureAfterSwitchRetainsOldCollectionForReconcileRecovery() {
        task.setGeneration(2L);
        task.setActiveGeneration(1L);
        task.setActiveCollection("teamdocs_chunks_d10_g1");
        ChunkHitVO chunk = chunk();
        when(chunks.listIndexableDocumentChunks(10L)).thenReturn(List.of(chunk));
        when(embeddings.embed(anyList())).thenReturn(List.of(List.of(1f, 0f)));
        doThrow(new RetrievalException("清理超时")).doNothing().when(vectors).dropCollection("teamdocs_chunks_d10_g1");

        worker.processNext();

        // 切换已完成，但因清理异常未调用 clearCleanupPending
        verify(mapper).completeAndSwitch(eq(10L), eq(2L), anyString(), anyLong(), eq(1), eq("teamdocs_chunks_d10_g2"), eq(1));
        verify(mapper, never()).clearCleanupPending(10L, 2L);
        verify(mapper, never()).fail(anyLong(), anyLong(), anyString(), anyLong(), anyLong());

        // 随后 reconcile 补偿清理成功
        VectorIndexMapper.Task pending = new VectorIndexMapper.Task();
        pending.setDocumentId(10L);
        pending.setActiveGeneration(2L);
        pending.setActiveParseVersion(1);
        pending.setActiveCollection("teamdocs_chunks_d10_g2");
        pending.setStaleCollection("teamdocs_chunks_d10_g1");
        when(mapper.pendingCleanupTasks()).thenReturn(List.of(pending));

        worker.reconcile();

        verify(vectors, times(2)).dropCollection("teamdocs_chunks_d10_g1");
        verify(mapper).clearCleanupPending(10L, 2L);
    }

    /** 并发 Worker 场景：只有一个 Worker 成功领取，另一个 Worker 不调用任何远端。 */
    @Test
    void concurrentWorkersOnlyOneClaimsAndExecutesTask() {
        when(chunks.listIndexableDocumentChunks(10L)).thenReturn(List.of(chunk()));
        when(embeddings.embed(anyList())).thenReturn(List.of(List.of(1f, 0f)));
        // worker 1 领取成功
        when(mapper.reserve(eq(10L), eq(1L), anyString(), anyLong(), anyLong())).thenReturn(true);
        worker.processNext();
        verify(vectors, times(1)).ensureCollection("teamdocs_chunks_d10_g1");

        // worker 2 竞争失败
        when(mapper.reserve(eq(10L), eq(1L), anyString(), anyLong(), anyLong())).thenReturn(false);
        worker.processNext();
        // 依然只有 worker 1 调用过 1 次
        verify(vectors, times(1)).ensureCollection("teamdocs_chunks_d10_g1");
    }

    /** 迟到的旧代次 Worker 切换失败时，保留旧集合并删除自身未生效的新集合。 */
    @Test
    void lateOldWorkerCannotOverwriteNewerGenerationOrTriggerCleanup() {
        task.setGeneration(2L);
        task.setActiveGeneration(1L);
        task.setActiveCollection("teamdocs_chunks_d10_g1");
        when(chunks.listIndexableDocumentChunks(10L)).thenReturn(List.of(chunk()));
        when(embeddings.embed(anyList())).thenReturn(List.of(List.of(1f, 0f)));
        // 模拟切换时数据库中 active_generation 已被新 Worker 更新，completeAndSwitch 返回 false
        when(mapper.completeAndSwitch(eq(10L), eq(2L), anyString(), anyLong(), any(), any(), anyInt())).thenReturn(false);

        worker.processNext();

        verify(vectors).dropCollection("teamdocs_chunks_d10_g2");
        verify(vectors, never()).dropCollection("teamdocs_chunks_d10_g1");
        verify(mapper, never()).clearCleanupPending(anyLong(), anyLong());
    }

    /** 租约过期后任务被新 Worker 接管，旧 Worker 在 verifyCurrent 时感知失败并退出。 */
    @Test
    void expiredLeaseAllowsReclaimAndBlocksStaleWorkerSwitchAndFail() {
        task.setGeneration(2L);
        task.setActiveGeneration(1L);
        task.setActiveCollection("teamdocs_chunks_d10_g1");
        when(chunks.listIndexableDocumentChunks(10L)).thenReturn(List.of(chunk()));
        when(embeddings.embed(anyList())).thenAnswer(call -> {
            // 租约过期或 token 不匹配
            when(mapper.verifyCurrent(eq(10L), eq(2L), anyString(), anyLong())).thenReturn(false);
            return List.of(List.of(1f, 0f));
        });

        worker.processNext();

        verify(vectors).dropCollection("teamdocs_chunks_d10_g2");
        verify(vectors, never()).dropCollection("teamdocs_chunks_d10_g1");
        verify(mapper, never()).completeAndSwitch(anyLong(), anyLong(), anyString(), anyLong(), any(), any(), anyInt());
        verify(mapper).fail(eq(10L), eq(2L), anyString(), anyLong(), anyLong());
    }

    /** 构造当前版本的合成正文。 */
    private ChunkHitVO chunk() {
        ChunkHitVO chunk = new ChunkHitVO();
        chunk.setChunkId(100L);
        chunk.setDocumentId(10L);
        chunk.setSpaceId(1L);
        chunk.setParseVersion(1);
        chunk.setExcerpt("合成正文");

        return chunk;
    }
}
