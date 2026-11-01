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
import java.util.UUID;

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

            for (VectorIndexMapper.Task pending : mapper.pendingCleanupTasks()) {
                try {
                    cleanupOldVectors(pending.getDocumentId(), pending.getStaleCollection(),
                            pending.getActiveCollection(), pending.getActiveParseVersion());
                    mapper.clearCleanupPending(pending.getDocumentId(), pending.getActiveGeneration());
                } catch (RuntimeException e) {
                    log.warn("补扫清理旧向量失败: {}", e.getClass().getSimpleName());
                }
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
        String claimToken = null;

        try {
            long now = System.currentTimeMillis();

            mapper.expireAttempts(now);
            task = mapper.next(now);

            if (task == null) {
                return;
            }

            claimToken = UUID.randomUUID().toString();
            long leaseUntil = now + TASK_TIMEOUT_MS;

            if (!mapper.reserve(task.getDocumentId(), task.getGeneration(), claimToken, now, leaseUntil)) {
                return;
            }

            VectorIndexMapper.Task currentTask = task;
            String currentClaimToken = claimToken;

            try (RetrievalContext ignored = RetrievalContext.open(leaseUntil,
                    () -> verifyCurrent(currentTask, currentClaimToken), hit -> { })) {
                sync(task, claimToken);
            }
        } catch (RuntimeException e) {
            if (task != null && claimToken != null) {
                try {
                    long failNow = System.currentTimeMillis();
                    mapper.fail(task.getDocumentId(), task.getGeneration(), claimToken, failNow, failNow + 30_000);
                } catch (RuntimeException ignored) {
                    log.warn("向量同步失败状态未能保存");
                }
            }

            log.warn("向量同步未完成: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * 使用独立蓝绿集合分批写入新代次向量，经数量、维度、索引与抽样查询验证后切换有效集合，再延迟清理旧集合。
     * @param task 已领取任务
     * @param claimToken 领取凭证
     */
    private void sync(VectorIndexMapper.Task task, String claimToken) {
        List<ChunkHitVO> rows = chunks.listIndexableDocumentChunks(task.getDocumentId());

        if (rows.isEmpty()) {
            verifyCurrent(task, claimToken);
            String oldCollection = task.getActiveCollection();
            boolean switched = mapper.completeAndSwitch(task.getDocumentId(), task.getGeneration(), claimToken,
                    System.currentTimeMillis(), task.getTargetParseVersion(), null, 0);
            if (switched) {
                try {
                    cleanupOldVectors(task.getDocumentId(), oldCollection, null, null);
                    mapper.clearCleanupPending(task.getDocumentId(), task.getGeneration());
                } catch (RuntimeException e) {
                    log.warn("删除文档向量清理延迟执行: {}", e.getClass().getSimpleName());
                }
            }
            return;
        }

        String targetCollection = resolveTargetCollection(task.getDocumentId(), task.getGeneration());
        Integer targetParseVersion = rows.get(0).getParseVersion();
        Long spaceId = rows.get(0).getSpaceId();
        boolean switched = false;

        try {
            vectors.ensureCollection(targetCollection);
            verifyCurrent(task, claimToken);

            int writtenCount = 0;
            List<Float> sampleVector = null;

            for (int start = 0; start < rows.size(); start += 16) {
                verifyCurrent(task, claimToken);

                List<ChunkHitVO> batch = rows.subList(start, Math.min(rows.size(), start + 16));
                List<List<Float>> encoded = embeddings.embed(batch.stream().map(this::vectorText).toList());

                if (sampleVector == null && encoded != null && !encoded.isEmpty()) {
                    sampleVector = encoded.get(0);
                }

                verifyCurrent(task, claimToken);
                vectors.upsert(targetCollection, batch, encoded);
                writtenCount += batch.size();
            }

            if (writtenCount != rows.size() || sampleVector == null) {
                throw new IllegalStateException("写入向量数量不完整");
            }

            verifyCurrent(task, claimToken);
            vectors.verifyCollection(targetCollection, spaceId, task.getDocumentId(), sampleVector);
            verifyCurrent(task, claimToken);

            switched = mapper.completeAndSwitch(task.getDocumentId(), task.getGeneration(), claimToken,
                    System.currentTimeMillis(), targetParseVersion, targetCollection, writtenCount);

            if (!switched) {
                dropQuietly(targetCollection);
                return;
            }
        } catch (RuntimeException e) {
            if (!switched) {
                dropQuietly(targetCollection);
            }
            throw e;
        }

        try {
            cleanupOldVectors(task.getDocumentId(), task.getActiveCollection(), targetCollection, targetParseVersion);
            mapper.clearCleanupPending(task.getDocumentId(), task.getGeneration());
        } catch (RuntimeException e) {
            log.warn("向量旧集合清理延迟执行: {}", e.getClass().getSimpleName());
        }
    }

    private String resolveTargetCollection(Long documentId, long generation) {
        String name = vectors.collectionName(documentId, generation);
        if (name != null && !name.isBlank()) {
            return name;
        }
        return "teamdocs_chunks_d" + documentId + "_g" + generation;
    }

    private String defaultCollectionName() {
        String name = vectors.defaultCollection();
        return (name != null && !name.isBlank()) ? name : "teamdocs_chunks";
    }

    private void cleanupOldVectors(Long documentId, String staleCollection, String activeCollection, Integer activeParseVersion) {
        String defaultCol = defaultCollectionName();
        if (activeCollection == null) {
            if (staleCollection != null && !staleCollection.isBlank() && !staleCollection.equals(defaultCol)) {
                vectors.dropCollection(staleCollection);
            } else {
                vectors.deleteDocument(documentId);
            }
            return;
        }

        if (staleCollection != null && !staleCollection.isBlank() && !staleCollection.equals(activeCollection)) {
            if (staleCollection.equals(defaultCol)) {
                vectors.deleteDocument(staleCollection, documentId);
            } else {
                vectors.dropCollection(staleCollection);
            }
        } else if (activeParseVersion != null) {
            vectors.deleteStaleChunks(documentId, activeParseVersion);
        }
    }

    private void dropQuietly(String collection) {
        if (collection == null || collection.isBlank() || collection.equals(defaultCollectionName())) {
            return;
        }
        try {
            vectors.dropCollection(collection);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * 拼接文档名和章节路径以增强语义向量表示。
     */
    private String vectorText(ChunkHitVO chunk) {
        if (chunk.getDocumentName() == null && chunk.getHeading() == null) {
            return chunk.getExcerpt() != null ? chunk.getExcerpt() : "";
        }
        StringBuilder sb = new StringBuilder();
        if (chunk.getDocumentName() != null && !chunk.getDocumentName().isBlank()) {
            sb.append("【文档: ").append(chunk.getDocumentName());
            if (chunk.getHeading() != null && !chunk.getHeading().isBlank()) {
                sb.append(" > ").append(chunk.getHeading());
            }
            sb.append("】\n");
        } else if (chunk.getHeading() != null && !chunk.getHeading().isBlank()) {
            sb.append("【章节: ").append(chunk.getHeading()).append("】\n");
        }
        sb.append(chunk.getExcerpt() != null ? chunk.getExcerpt() : "");
        return sb.toString();
    }

    /**
     * 阻止旧代次与过期租约继续调用，状态变化时补登记新目标。
     * @param task 当前任务
     * @param claimToken 领取凭证
     */
    private void verifyCurrent(VectorIndexMapper.Task task, String claimToken) {
        long now = System.currentTimeMillis();
        if (!mapper.verifyCurrent(task.getDocumentId(), task.getGeneration(), claimToken, now)) {
            throw new IllegalStateException("向量同步任务已被替换或租约已过期");
        }

        String signature = queue.signature(mapper.snapshot(task.getDocumentId()));

        if (!signature.equals(task.getTargetSignature())) {
            queue.enqueue(task.getDocumentId());

            throw new IllegalStateException("文档状态已变化");
        }
    }
}
