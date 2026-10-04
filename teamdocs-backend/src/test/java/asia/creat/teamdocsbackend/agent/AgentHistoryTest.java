package asia.creat.teamdocsbackend.agent;

import asia.creat.agent.AgentBudget;
import asia.creat.agent.AgentData;
import asia.creat.agent.AgentJson;
import asia.creat.agent.AgentTools;
import asia.creat.agent.AgentWorker;
import asia.creat.config.AgentProperties;
import asia.creat.mapper.AgentMapper;
import asia.creat.security.LoginUser;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentHistoryTest {
    private final AgentMapper mapper = mock(AgentMapper.class);
    private final AgentTools tools = mock(AgentTools.class);
    private final AgentJson json = mock(AgentJson.class);
    private final AgentProperties properties = new AgentProperties();
    private final AgentWorker worker = new AgentWorker(mapper, null, tools, null, json, properties,
            null, null, null, null, null, null, false, null);

    /** 默认允许50轮完整问答，并按时间顺序放入上下文。 */
    @Test
    void loadsFiftyTurnsInsteadOfHardCodedThree() {
        assertEquals(50, properties.getHistoryTurns());
        assertEquals(100000, properties.getHistoryMaxInputTokens());
        List<AgentData.Message> history = IntStream.iterate(50, id -> id - 1).limit(50)
                .mapToObj(id -> message(id, "回答" + id + "[C1]")).toList();
        when(mapper.history(3L, 50)).thenReturn(history);
        when(mapper.run(anyLong())).thenAnswer(call -> previous(call.getArgument(0), "问题" + call.getArgument(0)));
        List<ChatMessage> messages = load();
        assertEquals(100, messages.size());
        assertEquals("问题1", ((UserMessage) messages.get(0)).singleText());
        assertEquals("回答50", ((AiMessage) messages.get(99)).text());
        verify(mapper).history(3L, 50);
    }

    /** 删除的历史运行被跳过，已取得的问题快照不再次查库。 */
    @Test
    void skipsDeletedRunsAndReadsEachSurvivingRunOnlyOnce() {
        when(mapper.history(3L, 50)).thenReturn(List.of(message(2, "已删除"), message(1, "保留回答")));
        when(mapper.run(2L)).thenReturn(null);
        when(mapper.run(1L)).thenReturn(previous(1L, "保留问题"), null);
        List<ChatMessage> messages = assertDoesNotThrow(this::load);
        assertEquals(2, messages.size());
        assertEquals("保留问题", ((UserMessage) messages.get(0)).singleText());
        assertEquals("保留回答", ((AiMessage) messages.get(1)).text());
        verify(mapper, times(1)).run(1L);
    }

    /** 历史预算约束的是完整问答，不拆散用户与助手消息。 */
    @Test
    void respectsBudgetAndKeepsNewestCompletePairs() {
        String question = "Question newer", answer = "Reply newer";
        properties.setHistoryMaxInputTokens(AgentBudget.estimate(
                List.of(UserMessage.from(question), AiMessage.from(answer)), List.of()));
        when(mapper.history(3L, 50)).thenReturn(List.of(message(2, answer), message(1, "older answer")));
        when(mapper.run(2L)).thenReturn(previous(2L, question));
        when(mapper.run(1L)).thenReturn(previous(1L, "older question"));
        List<ChatMessage> messages = load();
        assertEquals(2, messages.size());
        assertEquals(question, ((UserMessage) messages.get(0)).singleText());
        assertTrue(AgentBudget.estimate(messages, List.of()) <= properties.getHistoryMaxInputTokens());
    }

    /** 历史轮数可以配置，缺失问题或失效来源不进入上下文。 */
    @Test
    void honorsConfigurationAndSkipsInvalidHistory() {
        properties.setHistoryTurns(70);
        when(mapper.history(3L, 70)).thenReturn(List.of(message(2, "缺问题"), message(1, "失效来源")));
        when(mapper.run(2L)).thenReturn(previous(2L, null));
        when(mapper.run(1L)).thenReturn(previous(1L, "问题"));
        when(tools.currentDependencies(anyLong(), any(), anyList())).thenReturn(true);
        assertTrue(load().isEmpty());
        verify(mapper).history(3L, 70);
    }

    /** 关闭历史时不查询历史表。 */
    @Test
    void zeroHistoryLimitSkipsLoading() {
        properties.setHistoryTurns(0);
        assertTrue(load().isEmpty());
        verify(mapper, never()).history(anyLong(), anyInt());
    }

    /** 用已授权运行的参数验证历史装配，不启动线程或模型。 */
    private List<ChatMessage> load() {
        when(json.dependencies(anyString())).thenReturn(List.of());
        AgentData.Run run = new AgentData.Run();
        run.setSessionId(3L);
        run.setSpaceId(1L);
        run.setMaxInputTokens(1_000_000);
        List<ChatMessage> messages = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(worker, "history", run, new LoginUser(7L, "test"), new AgentTools.State(), messages);
        return messages;
    }

    /** 创建已完成的助手历史消息。 */
    private AgentData.Message message(long runId, String body) {
        AgentData.Message message = new AgentData.Message();
        message.setRunId(runId);
        message.setBody(body);
        message.setDependencies("[]");
        return message;
    }

    /** 创建历史运行的问题快照。 */
    private AgentData.Run previous(Long id, String question) {
        AgentData.Run run = new AgentData.Run();
        run.setId(id);
        run.setQuestion(question);
        return run;
    }
}
