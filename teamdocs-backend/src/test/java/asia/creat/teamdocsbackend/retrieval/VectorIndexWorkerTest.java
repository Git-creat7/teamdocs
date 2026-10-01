package asia.creat.teamdocsbackend.retrieval;

import asia.creat.config.EmbeddingProperties;
import asia.creat.mapper.DocumentContentMapper;
import asia.creat.mapper.VectorIndexMapper;
import asia.creat.retrieval.MilvusVectorClient;
import asia.creat.retrieval.RetrievalException;
import asia.creat.retrieval.SiliconFlowEmbeddingClient;
import asia.creat.retrieval.VectorIndexQueue;
import asia.creat.retrieval.VectorIndexWorker;
import asia.creat.vo.ChunkHitVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VectorIndexWorkerTest {
    private final VectorIndexMapper mapper = mock(VectorIndexMapper.class);
    private final DocumentContentMapper chunks = mock(DocumentContentMapper.class);
    private final SiliconFlowEmbeddingClient embeddings = mock(SiliconFlowEmbeddingClient.class);
    private final MilvusVectorClient vectors = mock(MilvusVectorClient.class);
    private final EmbeddingProperties properties = new EmbeddingProperties();
    private final asia.creat.config.MilvusProperties milvus = new asia.creat.config.MilvusProperties();
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
        task.setState("PENDING");
        task.setTargetSignature(queue.signature(snapshot));
        when(mapper.snapshot(10L)).thenReturn(snapshot);
        when(mapper.next(anyLong())).thenReturn(task);
        when(mapper.task(10L)).thenReturn(task);
        when(mapper.reserve(eq(10L), eq(1L), anyLong(), anyLong())).thenReturn(1);
        when(embeddings.enabled()).thenReturn(true);
        when(vectors.enabled()).thenReturn(true);
    }

    /** 缺少向量化授权时不领取任务，也不访问 Milvus。 */
    @Test
    void disabledEmbeddingDoesNotConsumeAttempts() {
        when(embeddings.enabled()).thenReturn(false);
        worker.processNext();
        verifyNoInteractions(mapper, chunks);
        verify(vectors, never()).ensureCollection();
    }

    /** 先持久化尝试，再清理旧向量、生成并发布新向量。 */
    @Test
    void persistsAttemptBeforeAnyRemoteMutation() {
        ChunkHitVO chunk = chunk();
        when(chunks.listIndexableDocumentChunks(10L)).thenReturn(List.of(chunk));
        when(embeddings.embed(List.of("合成正文"))).thenReturn(List.of(List.of(1f, 0f)));
        worker.processNext();
        var order = inOrder(mapper, vectors, embeddings);
        order.verify(mapper).reserve(eq(10L), eq(1L), anyLong(), anyLong());
        order.verify(vectors).ensureCollection();
        order.verify(vectors).deleteDocument(10L);
        order.verify(embeddings).embed(List.of("合成正文"));
        order.verify(vectors).upsert(List.of(chunk), List.of(List.of(1f, 0f)));
        order.verify(mapper).complete(10L, 1L);
        verify(mapper, never()).fail(anyLong(), anyLong(), anyLong());
    }

    /** 删除任务只清理向量，不调用外部 Embedding。 */
    @Test
    void deletedDocumentDoesNotSendText() {
        when(mapper.snapshot(10L)).thenReturn(null);
        task.setTargetSignature("deleted");
        when(chunks.listIndexableDocumentChunks(10L)).thenReturn(List.of());
        worker.processNext();
        verify(vectors).deleteDocument(10L);
        verify(embeddings, never()).embed(anyList());
        verify(mapper).complete(10L, 1L);
    }

    /** 向量化期间文档版本变化时，不允许迟到结果继续写入。 */
    @Test
    void changedVersionPreventsLatePublication() {
        when(chunks.listIndexableDocumentChunks(10L)).thenReturn(List.of(chunk()));
        when(embeddings.embed(anyList())).thenAnswer(call -> {
            snapshot.setParseVersion(2);
            return List.of(List.of(1f, 0f));
        });
        worker.processNext();
        verify(vectors, never()).upsert(anyList(), anyList());
        verify(mapper, never()).complete(anyLong(), anyLong());
        verify(mapper).enqueue(10L, queue.signature(snapshot));
    }

    /** 新代次替换旧任务时，旧任务不能完成新待办。 */
    @Test
    void newerGenerationSurvivesOldWorker() {
        when(chunks.listIndexableDocumentChunks(10L)).thenReturn(List.of(chunk()));
        when(embeddings.embed(anyList())).thenAnswer(call -> {
            VectorIndexMapper.Task newer = new VectorIndexMapper.Task();
            newer.setGeneration(2);
            newer.setState("PENDING");
            when(mapper.task(10L)).thenReturn(newer);
            return List.of(List.of(1f, 0f));
        });
        worker.processNext();
        verify(vectors, never()).upsert(anyList(), anyList());
        verify(mapper, never()).complete(anyLong(), anyLong());
        verify(mapper).fail(eq(10L), eq(1L), anyLong());
    }

    /** 远端失败只记录向量任务失败，不改变正文生命周期。 */
    @Test
    void remoteFailureIsRecordedWithoutPublishingSuccess() {
        when(chunks.listIndexableDocumentChunks(10L)).thenReturn(List.of(chunk()));
        doThrow(new RetrievalException("测试故障")).when(vectors).ensureCollection();
        worker.processNext();
        verify(mapper).fail(eq(10L), eq(1L), anyLong());
        verify(mapper, never()).complete(anyLong(), anyLong());
        verify(chunks).listIndexableDocumentChunks(10L);
        verifyNoMoreInteractions(chunks);
    }

    /** 领取竞争失败时不发生任何远端调用。 */
    @Test
    void reservationFailureDoesNotRunTask() {
        when(mapper.reserve(eq(10L), eq(1L), anyLong(), anyLong())).thenReturn(0);
        worker.processNext();
        verify(vectors, never()).ensureCollection();
        verify(embeddings, never()).embed(anyList());
    }

    /** 补扫既包含当前文档，也包含曾建立向量但已彻底删除的文档。 */
    @Test
    void reconciliationRepairsMissingEventsAndPurges() {
        when(mapper.documentIds(0)).thenReturn(List.of(10L));
        when(mapper.deletedDocumentIds()).thenReturn(List.of(11L));
        worker.reconcile();
        verify(mapper).enqueue(10L, queue.signature(snapshot));
        verify(mapper).enqueue(11L, "deleted");
    }

    /** 集合切换必须产生新目标，否则已有 DONE 任务不会重建向量。 */
    @Test
    void collectionChangeInvalidatesCompletedTarget() {
        String before = queue.signature(snapshot);
        milvus.setCollection("new_vector_collection");
        org.junit.jupiter.api.Assertions.assertNotEquals(before, queue.signature(snapshot));
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
