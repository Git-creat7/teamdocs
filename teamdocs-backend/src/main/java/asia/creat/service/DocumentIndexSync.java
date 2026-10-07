package asia.creat.service;

import asia.creat.retrieval.VectorIndexQueue;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 先提交业务，再读取 MySQL 当前状态更新索引；索引失败不能覆盖主业务结果。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentIndexSync {
    private final ChunkIndex index;
    private final Optional<VectorIndexQueue> vectors;

    /**
     * 登记向量待办，并在事务提交后同步关键词索引。
     * @param documentId 文档ID
     */
    public void afterCommit(Long documentId) {
        vectors.ifPresent(queue -> {
            try {
                queue.enqueue(documentId);
            } catch (RuntimeException e) {
                log.warn("文档 {} 向量待办登记失败，后续补扫恢复: {}", documentId, e.getClass().getSimpleName());
            }
        });

        if (!index.enabled()) {
            return;
        }

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sync(documentId);
                }
            });
        } else {
            sync(documentId);
        }
    }

    private void sync(Long documentId) {
        try {
            index.syncDocument(documentId);
        } catch (RuntimeException e) {
            log.warn("文档 {} 索引同步失败，可通过重建恢复，不影响 MySQL: {}", documentId, e.getClass().getSimpleName());
        }
    }
}
