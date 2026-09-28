package asia.creat.service;

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

    public void afterCommit(Long documentId) {
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
