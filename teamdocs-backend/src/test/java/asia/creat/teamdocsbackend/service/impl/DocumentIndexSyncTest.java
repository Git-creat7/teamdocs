package asia.creat.teamdocsbackend.service.impl;

import asia.creat.service.ChunkIndex;
import asia.creat.service.DocumentIndexSync;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentIndexSyncTest {
    private final ChunkIndex index = mock(ChunkIndex.class);
    private final DocumentIndexSync sync = new DocumentIndexSync(index);

    @AfterEach
    void clear() { TransactionSynchronizationManager.clear(); }

    @Test
    void waitsForCommitAndDoesNotPublishOnRollback() {
        when(index.enabled()).thenReturn(true);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        sync.afterCommit(10L);
        verify(index, never()).syncDocument(anyLong());
        TransactionSynchronization callback = TransactionSynchronizationManager.getSynchronizations().get(0);
        callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(index, never()).syncDocument(anyLong());
    }

    @Test
    void committedTransactionTriggersSyncAndIsolatesIndexFailure() {
        when(index.enabled()).thenReturn(true);
        doThrow(new IllegalStateException("ES offline")).when(index).syncDocument(10L);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        sync.afterCommit(10L);
        assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit());
        verify(index).syncDocument(10L);
    }

    @Test
    void syncsWithoutTransactionAndDisabledIndexDoesNothing() {
        sync.afterCommit(10L);
        verify(index, never()).syncDocument(anyLong());
        when(index.enabled()).thenReturn(true);
        sync.afterCommit(10L);
        verify(index).syncDocument(10L);
    }
}
