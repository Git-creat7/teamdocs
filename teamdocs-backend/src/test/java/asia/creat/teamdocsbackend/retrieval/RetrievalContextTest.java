package asia.creat.teamdocsbackend.retrieval;

import asia.creat.retrieval.RetrievalContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RetrievalContextTest {
    /** 请求超时不能超过当前运行的剩余时间。 */
    @Test
    void limitsHttpTimeoutByRunDeadline() {
        try (var scope = RetrievalContext.open(System.currentTimeMillis() + 500, () -> { }, hit -> { })) {
            long timeout = RetrievalContext.timeoutMillis(10_000);
            assertTrue(timeout > 0 && timeout <= 500);
        }
        assertEquals(10_000, RetrievalContext.timeoutMillis(10_000));
    }

    /** 超时后不允许再发起下一个请求。 */
    @Test
    void expiredRunIsNotARecoverableRemoteFailure() {
        try (var scope = RetrievalContext.open(System.currentTimeMillis() - 1, () -> { }, hit -> { })) {
            assertThrows(IllegalStateException.class, () -> RetrievalContext.timeoutMillis(1000));
        }
    }

    /** 嵌套上下文结束后恢复外层检查，线程复用不会遗留旧运行状态。 */
    @Test
    void nestedScopesRestoreAndThenClearContext() {
        AtomicInteger outer = new AtomicInteger();
        AtomicInteger inner = new AtomicInteger();
        try (var first = RetrievalContext.open(Long.MAX_VALUE, outer::incrementAndGet, hit -> { })) {
            try (var second = RetrievalContext.open(Long.MAX_VALUE, inner::incrementAndGet, hit -> { })) {
                RetrievalContext.check();
            }
            RetrievalContext.check();
        }
        RetrievalContext.check();
        assertEquals(1, outer.get());
        assertEquals(1, inner.get());
    }
}
