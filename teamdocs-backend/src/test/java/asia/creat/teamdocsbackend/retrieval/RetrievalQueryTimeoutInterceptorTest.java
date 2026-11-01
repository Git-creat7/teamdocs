package asia.creat.teamdocsbackend.retrieval;

import asia.creat.retrieval.RetrievalContext;
import asia.creat.retrieval.RetrievalQueryTimeoutInterceptor;
import org.apache.ibatis.plugin.Invocation;
import org.junit.jupiter.api.Test;

import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RetrievalQueryTimeoutInterceptorTest {

    private final RetrievalQueryTimeoutInterceptor interceptor = new RetrievalQueryTimeoutInterceptor();

    @Test
    void proceedsWithoutModifyingWhenNoBoundedDeadline() throws Throwable {
        Invocation invocation = mock(Invocation.class);
        Statement statement = mock(Statement.class);
        when(invocation.proceed()).thenReturn(statement);

        Object result = interceptor.intercept(invocation);

        assertSame(statement, result);
        verify(statement, never()).setQueryTimeout(anyInt());
    }

    @Test
    void throwsImmediatelyWhenRetrievalDeadlineExpired() {
        Invocation invocation = mock(Invocation.class);

        try (var ignored = RetrievalContext.open(System.currentTimeMillis() - 100, () -> { }, hit -> { })) {
            assertThrows(IllegalStateException.class, () -> interceptor.intercept(invocation));
        }
    }

    @Test
    void dynamicallyBoundsStatementQueryTimeout() throws Throwable {
        Invocation invocation = mock(Invocation.class);
        Statement statement = mock(Statement.class);
        when(invocation.proceed()).thenReturn(statement);
        when(statement.getQueryTimeout()).thenReturn(0);

        try (var ignored = RetrievalContext.open(System.currentTimeMillis() + 1500, () -> { }, hit -> { })) {
            interceptor.intercept(invocation);

            verify(statement).setQueryTimeout(2); // ceil(1500 / 1000.0) = 2
        }
    }

    @Test
    void tightensStaticXmlTimeoutWhenRemainingBudgetIsSmaller() throws Throwable {
        Invocation invocation = mock(Invocation.class);
        Statement statement = mock(Statement.class);
        when(invocation.proceed()).thenReturn(statement);
        when(statement.getQueryTimeout()).thenReturn(5);

        try (var ignored = RetrievalContext.open(System.currentTimeMillis() + 800, () -> { }, hit -> { })) {
            interceptor.intercept(invocation);

            verify(statement).setQueryTimeout(1); // ceil(800 / 1000.0) = 1 < 5
        }
    }

    @Test
    void preservesStaticXmlTimeoutWhenRemainingBudgetIsLarger() throws Throwable {
        Invocation invocation = mock(Invocation.class);
        Statement statement = mock(Statement.class);
        when(invocation.proceed()).thenReturn(statement);
        when(statement.getQueryTimeout()).thenReturn(2);

        try (var ignored = RetrievalContext.open(System.currentTimeMillis() + 10_000, () -> { }, hit -> { })) {
            interceptor.intercept(invocation);

            // dynamic is 10 > 2, so should not overwrite statement's 2
            verify(statement, never()).setQueryTimeout(anyInt());
        }
    }

    @Test
    void doesNotTriggerCheckpointOrRecurseDuringStatementPrepare() throws Throwable {
        AtomicInteger checkpointRuns = new AtomicInteger();
        Invocation invocation = mock(Invocation.class);
        Statement statement = mock(Statement.class);
        when(invocation.proceed()).thenReturn(statement);

        try (var ignored = RetrievalContext.open(System.currentTimeMillis() + 5000,
                checkpointRuns::incrementAndGet, hit -> { })) {
            // Executing the interceptor must NOT invoke checkpoint.run()
            interceptor.intercept(invocation);

            assertEquals(0, checkpointRuns.get(), "Statement preparation must not trigger business checkpoints");
        }
    }
}
