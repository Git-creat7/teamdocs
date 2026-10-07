package asia.creat.teamdocsbackend.agent;

import asia.creat.agent.AgentData.*;
import asia.creat.agent.AgentEventHub;
import asia.creat.agent.AgentReasoningRegistry;
import asia.creat.agent.AgentRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentReasoningRegistryTest {
    /** 运行内容只进入内存，除了读取运行行不调用任何持久化方法。 */
    @Test
    void keepsProgressOnlyInMemoryAndChecksOwnership() {
        AgentRepository mapper = mock(AgentRepository.class);
        AgentEventHub events = mock(AgentEventHub.class);
        AgentReasoningRegistry registry = new AgentReasoningRegistry(mapper, events);
        Run run = run(1);

        when(mapper.lockRun(1L)).thenReturn(run);

        assertTrue(registry.publish(run, new ReasoningProgress("临时思考", 20L, false), List.of()));
        assertEquals("临时思考", registry.get(run).progress().content());
        verify(mapper).lockRun(1L);
        verifyNoMoreInteractions(mapper);
        verify(events).afterCommit(1L, "reasoning_updated");

        Run other = run(1);
        other.setUserId(99L);

        assertNull(registry.get(other));
        registry.forgetSession(3L);

        assertNull(registry.get(run));
    }

    /** 取消或超时的运行不能再更新已有思考。 */
    @Test
    void rejectsLateContentAfterTerminalTransition() {
        AgentRepository mapper = mock(AgentRepository.class);
        AgentEventHub events = mock(AgentEventHub.class);
        AgentReasoningRegistry registry = new AgentReasoningRegistry(mapper, events);
        Run run = run(1);

        when(mapper.lockRun(1L)).thenReturn(run);

        assertTrue(registry.publish(run, new ReasoningProgress("取消前", 10L, false), List.of()));
        run.setStatus("CANCELLED");

        assertFalse(registry.publish(run, new ReasoningProgress("迟到内容", 20L, false), List.of()));
        assertEquals("取消前", registry.get(run).progress().content());

        run.setStatus("RUNNING");
        run.setDeadlineMs(System.currentTimeMillis() - 1);

        assertFalse(registry.publish(run, new ReasoningProgress("超时内容", 30L, false), List.of()));
        verify(events, times(1)).afterCommit(anyLong(), anyString());
    }

    /** 内存有固定上限，不会因历史运行无限增长。 */
    @Test
    void boundsNumberOfCachedRuns() {
        AgentRepository mapper = mock(AgentRepository.class);
        AgentReasoningRegistry registry = new AgentReasoningRegistry(mapper, mock(AgentEventHub.class));

        for (long id = 1; id <= 33; id++) {
            Run run = run(id);

            when(mapper.lockRun(id)).thenReturn(run);

            assertTrue(registry.publish(run, new ReasoningProgress("测试", 0L, false), List.of()));
        }

        assertNull(registry.get(run(1)));
        assertNotNull(registry.get(run(33)));
    }

    /** 构造当前仍有效的用户运行。 */
    private Run run(long id) {
        Run run = new Run();
        run.setId(id);
        run.setUserId(7L);
        run.setSpaceId(1L);
        run.setSessionId(3L);
        run.setStatus("RUNNING");
        run.setDeadlineMs(System.currentTimeMillis() + 60_000);

        return run;
    }
}
