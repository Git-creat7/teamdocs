package asia.creat.teamdocsbackend.agent;

import asia.creat.agent.AgentData.*;
import asia.creat.agent.AgentFailure;
import asia.creat.agent.AgentReasoningRegistry;
import asia.creat.agent.AgentReasoningState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentReasoningStateTest {
    /** 连续快照不能重复拼接，多轮只累加各轮思考输出耗时。 */
    @Test
    void accumulatesCallsAndThrottlesSnapshotsWithoutDuplication() {
        Run run = new Run();
        AgentReasoningRegistry registry = mock(AgentReasoningRegistry.class);

        when(registry.publish(eq(run), any(), anyList())).thenReturn(true);

        AtomicLong clock = new AtomicLong();
        AgentReasoningState state = new AgentReasoningState(run, registry, List::of, () -> { }, clock::get);
        var first = state.beginCall();
        first.onReasoning("检查", 0L, false);
        first.onReasoning("检查资料", 20L, false);
        verify(registry, times(1)).publish(eq(run), any(), anyList());
        clock.set(TimeUnit.MILLISECONDS.toNanos(300));
        first.onReasoning("检查资料后确认", 300L, false);
        state.flush();
        verify(registry, times(2)).publish(eq(run), any(), anyList());

        var second = state.beginCall();
        second.onReasoning("继续核对", 40L, false);
        state.flush();

        assertEquals("检查资料后确认\n\n继续核对", state.snapshot().content());
        assertEquals(340L, state.snapshot().durationMs());
    }

    /** 最后一批思考后模型只生成正文时，等待周期仍能及时刷新尾批。 */
    @Test
    void pollingFlushPublishesTrailingReasoningWithoutNewModelChunks() {
        Run run = new Run();
        AgentReasoningRegistry registry = mock(AgentReasoningRegistry.class);

        when(registry.publish(eq(run), any(), anyList())).thenReturn(true);

        AtomicLong clock = new AtomicLong();
        AgentReasoningState state = new AgentReasoningState(run, registry, List::of, () -> { }, clock::get);
        var observer = state.beginCall();
        observer.onReasoning("首批", 0L, false);
        clock.set(TimeUnit.MILLISECONDS.toNanos(20));
        observer.onReasoning("首批和尾批", 20L, false);
        verify(registry, times(1)).publish(eq(run), any(), anyList());
        clock.set(TimeUnit.MILLISECONDS.toNanos(250));
        state.flush();
        verify(registry).publish(run, new ReasoningProgress("首批和尾批", 20L, false), List.of());
    }

    /** 任一轮无法测量时，累计耗时保持未知，不把部分时段当成总耗时。 */
    @Test
    void preservesUnknownDurationAndBoundsCombinedContent() {
        AgentReasoningRegistry registry = mock(AgentReasoningRegistry.class);

        when(registry.publish(any(), any(), anyList())).thenReturn(true);

        AgentReasoningState state = new AgentReasoningState(new Run(), registry, List::of, () -> { });

        state.beginCall().onReasoning("完整响应", null, false);
        state.beginCall().onReasoning("a".repeat(32767) + "😀", 10L, true);

        assertNull(state.snapshot().durationMs());
        assertTrue(state.snapshot().truncated());
        assertTrue(state.snapshot().content().length() <= 32768);
        assertFalse(Character.isHighSurrogate(state.snapshot().content().charAt(state.snapshot().content().length() - 1)));
    }

    /** 取消后发布被拒绝，不能继续积累可见进度。 */
    @Test
    void rejectedRuntimeUpdateStopsObservation() {
        AgentReasoningRegistry registry = mock(AgentReasoningRegistry.class);
        AgentReasoningState state = new AgentReasoningState(new Run(), registry, List::of, () -> { });

        assertThrows(AgentFailure.class, () -> state.beginCall().onReasoning("开始", 0L, false));
    }

    /** 没有模型思考时不创建空进度，也不推送空卡片。 */
    @Test
    void absentReasoningDoesNotPublish() {
        AgentReasoningRegistry registry = mock(AgentReasoningRegistry.class);
        AgentReasoningState state = new AgentReasoningState(new Run(), registry, List::of, () -> { });

        state.beginCall().onReasoning("", null, false);
        state.flush();

        assertNull(state.snapshot().content());
        assertNull(state.snapshot().durationMs());
        verifyNoInteractions(registry);
    }
}
