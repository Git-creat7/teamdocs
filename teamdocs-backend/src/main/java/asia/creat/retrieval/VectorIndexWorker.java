package asia.creat.retrieval;

import asia.creat.mapper.DocumentContentMapper;
import asia.creat.mapper.VectorIndexMapper;
import asia.creat.vo.ChunkHitVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "teamdocs.milvus", name = "enabled", havingValue = "true", matchIfMissing = true)
public class VectorIndexWorker {
    private static final long TASK_TIMEOUT_MS = 120_000;
    private final VectorIndexMapper mapper;
    private final DocumentContentMapper chunks;
    private final VectorIndexQueue queue;
    private final SiliconFlowEmbeddingClient embeddings;
    private final MilvusVectorClient vectors;
    private long afterId;

    /**
     * 有界补扫遗漏事件，首次启用时也能补齐已有文档。
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000, scheduler = "vectorTaskScheduler")
    public void reconcile() {
        if (!embeddings.enabled() || !vectors.enabled()) {
            return;
        }
        try {
            List<Long> ids = mapper.documentIds(afterId);
            for (Long id : ids) {
                queue.enqueue(id);
                afterId = id;
            }
            if (ids.isEmpty()) {
                afterId = 0;
            }
            for (Long id : mapper.deletedDocumentIds()) {
                queue.enqueue(id);
            }
        } catch (RuntimeException e) {
            log.warn("向量同步补扫失败，请核对初始化表: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * 串行处理一个任务，外部故障不影响文档业务。
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 15_000, scheduler = "vectorTaskScheduler")
    public void processNext() {
        if (!embeddings.enabled() || !vectors.enabled()) {
            return;
        }
        VectorIndexMapper.Task task = null;
        try {
            long now = System.currentTimeMillis();
            mapper.expireAttempts(now);
            task = mapper.next(now);
            if (task == null || mapper.reserve(task.getDocumentId(), task.getGeneration(), now, now + TASK_TIMEOUT_MS) != 1) {
                return;
            }
            VectorIndexMapper.Task currentTask = task;
            try (RetrievalContext ignored = RetrievalContext.open(now + TASK_TIMEOUT_MS,
                    () -> verifyCurrent(currentTask), hit -> { })) {
                sync(task);
                verifyCurrent(task);
                mapper.complete(task.getDocumentId(), task.getGeneration());
            }
        } catch (RuntimeException e) {
            if (task != null) {
                try {
                    mapper.fail(task.getDocumentId(), task.getGeneration(), System.currentTimeMillis() + 30_000);
                } catch (RuntimeException ignored) {
                    log.warn("向量同步失败状态未能保存");
                }
            }
            log.warn("向量同步未完成: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * 清除旧向量后按小批次发布当前正文，不持有跨 HTTP 的数据库事务。
     * @param task 已领取任务
     */
    private void sync(VectorIndexMapper.Task task) {
        List<ChunkHitVO> rows = chunks.listIndexableDocumentChunks(task.getDocumentId());
        vectors.ensureCollection();
        verifyCurrent(task);
        vectors.deleteDocument(task.getDocumentId());
        for (int start = 0; start < rows.size(); start += 16) {
            verifyCurrent(task);
            List<ChunkHitVO> batch = rows.subList(start, Math.min(rows.size(), start + 16));
            List<List<Float>> encoded = embeddings.embed(batch.stream().map(ChunkHitVO::getExcerpt).toList());
            verifyCurrent(task);
            vectors.upsert(batch, encoded);
        }
    }

    /**
     * 阻止旧代次继续调用，状态变化时补登记新目标。
     * @param task 当前任务
     */
    private void verifyCurrent(VectorIndexMapper.Task task) {
        VectorIndexMapper.Task current = mapper.task(task.getDocumentId());
        if (current == null || current.getGeneration() != task.getGeneration()
                || !"PENDING".equals(current.getState())) {
            throw new IllegalStateException("向量同步任务已被替换");
        }
        String signature = queue.signature(mapper.snapshot(task.getDocumentId()));
        if (!signature.equals(task.getTargetSignature())) {
            queue.enqueue(task.getDocumentId());
            throw new IllegalStateException("文档状态已变化");
        }
    }
}
