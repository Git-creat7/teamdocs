package asia.creat.teamdocsbackend.agent;

import asia.creat.agent.AgentBudget;
import asia.creat.agent.AgentData;
import asia.creat.agent.AgentJson;
import asia.creat.agent.AgentRepository;
import asia.creat.agent.AgentTools;
import asia.creat.agent.AgentWorker;
import asia.creat.agent.AttachmentMessage;
import asia.creat.security.LoginUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentHistoryTest {
    private final AgentRepository mapper = mock(AgentRepository.class);
    private final AgentTools tools = mock(AgentTools.class);
    private final AgentJson json = new AgentJson(new ObjectMapper());
    private final AgentWorker worker = new AgentWorker(mapper, null, tools, null, json,
            null, null, null, null, null, null, false, null, null, null, null, null);

    /** 历史超过50轮时继续分页，并按时间顺序放入上下文。 */
    @Test
    void loadsMultiplePagesBeyondFiftyTurns() {
        List<AgentData.Message> history = IntStream.iterate(120, id -> id - 1).limit(120)
                .mapToObj(id -> message(id, "回答" + id + "[C1]")).toList();

        when(mapper.history(3L, Long.MAX_VALUE, 50)).thenReturn(history.subList(0, 50));
        when(mapper.history(3L, 71L, 50)).thenReturn(history.subList(50, 100));
        when(mapper.history(3L, 21L, 50)).thenReturn(history.subList(100, 120));
        when(mapper.run(anyLong())).thenAnswer(call -> previous(call.getArgument(0), "问题" + call.getArgument(0)));

        List<ChatMessage> messages = load();

        assertEquals(240, messages.size());
        assertEquals("问题1", ((UserMessage) messages.get(0)).singleText());
        assertEquals("回答120", decodedAnswer(messages.get(239)));
        verify(mapper).history(3L, 71L, 50);
        verify(mapper).history(3L, 21L, 50);
        verify(mapper).history(3L, Long.MAX_VALUE, 50);
    }

    /** 删除的历史运行被跳过，已取得的问题快照不再次查库。 */
    @Test
    void skipsDeletedRunsAndReadsEachSurvivingRunOnlyOnce() {
        when(mapper.history(3L, Long.MAX_VALUE, 50)).thenReturn(List.of(message(2, "已删除"), message(1, "保留回答")));
        when(mapper.run(2L)).thenReturn(null);
        when(mapper.run(1L)).thenReturn(previous(1L, "保留问题"), null);

        List<ChatMessage> messages = assertDoesNotThrow(this::load);

        assertEquals(2, messages.size());
        assertEquals("保留问题", ((UserMessage) messages.get(0)).singleText());
        assertEquals("保留回答", decodedAnswer(messages.get(1)));
        verify(mapper, times(1)).run(1L);
    }

    /** 历史预算约束的是完整问答，不拆散用户与助手消息。 */
    @Test
    void respectsBudgetAndKeepsNewestCompletePairs() {
        String question = "Question newer", answer = "Reply newer";

        AiMessage reply = AiMessage.from(json.write(Map.of("answer", answer, "citations", List.of())));
        int limit = AgentBudget.estimate(List.of(SystemMessage.from("system"),
                UserMessage.from(question), reply, UserMessage.from("current")), List.of());
        when(mapper.history(3L, Long.MAX_VALUE, 50)).thenReturn(List.of(message(2, answer), message(1, "older answer")));
        when(mapper.run(2L)).thenReturn(previous(2L, question));
        when(mapper.run(1L)).thenReturn(previous(1L, "older question"));

        List<ChatMessage> messages = loadWithin(limit);

        assertEquals(2, messages.size());
        assertEquals(question, ((UserMessage) messages.get(0)).singleText());
        assertEquals(limit, AgentBudget.estimate(List.of(SystemMessage.from("system"),
                messages.get(0), messages.get(1), UserMessage.from("current")), List.of()));
    }

    /** 缺失问题或失效来源不进入上下文。 */
    @Test
    void skipsInvalidHistory() {
        when(mapper.history(3L, Long.MAX_VALUE, 50)).thenReturn(List.of(message(2, "缺问题"), message(1, "失效来源")));
        when(mapper.run(2L)).thenReturn(previous(2L, null));
        when(mapper.run(1L)).thenReturn(previous(1L, "问题"));
        when(tools.currentDependencies(anyLong(), any(), anyList())).thenReturn(true);

        assertTrue(load().isEmpty());
        verify(mapper).history(3L, Long.MAX_VALUE, 50);
    }

    /** 限定范围和附件提问保持原有隔离，不加载普通历史。 */
    @Test
    void scopedAndAttachmentQuestionsSkipHistory() {
        var run = AgentData.Run.builder().sessionId(3L).spaceId(1L).maxInputTokens(10000).build();
        var state = new AgentTools.State();
        ReflectionTestUtils.setField(state, "scopeIds", List.of(1L));
        List<ChatMessage> messages = new ArrayList<>(List.of(SystemMessage.from("system")));
        int pairs = ReflectionTestUtils.invokeMethod(worker, "history", run, new LoginUser(7L, "test"),
                state, messages, UserMessage.from("current"), List.of());
        assertEquals(0, pairs);

        pairs = ReflectionTestUtils.invokeMethod(worker, "history", run, new LoginUser(7L, "test"),
                new AgentTools.State(), messages, new AttachmentMessage("current", List.of()), List.of());
        assertEquals(0, pairs);
        verify(mapper, never()).history(anyLong(), anyLong(), anyInt());
    }

    /** 历史示例与最终 JSON 协议一致，旧引用不能冒充本轮来源。 */
    @Test
    void historyUsesJsonEnvelopesAndEscapesMarkdownWithoutReusingCitations() {
        String body = "代码：\n```json\n{\"key\":\"value\"}\n```\n旧结论[C1][C2]";
        when(mapper.history(3L, Long.MAX_VALUE, 50)).thenReturn(List.of(message(1, body)));
        when(mapper.run(1L)).thenReturn(previous(1L, "之前的问题"));

        var messages = load();
        assertEquals(2, messages.size());
        assertInstanceOf(UserMessage.class, messages.get(0));
        assertEquals(body.replace("[C1]", "").replace("[C2]", ""), decodedAnswer(messages.get(1)));
    }

    /** 可用输入容量已经扣除生成预留，不再另扣三分之一。 */
    @Test
    void keepsAllHistoryWhenTheCompleteRequestFits() {
        when(mapper.history(3L, Long.MAX_VALUE, 50)).thenReturn(List.of(message(1, "answer")));
        when(mapper.run(1L)).thenReturn(previous(1L, "question"));
        var system = SystemMessage.from("system");
        var question = UserMessage.from("current");
        var reply = AiMessage.from(json.write(Map.of("answer", "answer", "citations", List.of())));
        int limit = AgentBudget.estimate(List.of(system, UserMessage.from("question"), reply, question), List.of());
        var run = AgentData.Run.builder().sessionId(3L).spaceId(1L).maxInputTokens(limit).build();
        List<ChatMessage> messages = new ArrayList<>(List.of(system));

        int pairs = ReflectionTestUtils.invokeMethod(worker, "history", run, new LoginUser(7L, "test"),
                new AgentTools.State(), messages, question, List.of());

        assertEquals(1, pairs);
        messages.add(question);
        assertEquals(limit, AgentBudget.estimate(messages, List.of()));
    }

    @Test
    void historyCanExceedTheFormerHundredThousandTokenLimit() {
        String body = "answer ".repeat(1000);
        String question = "question ".repeat(200);
        List<AgentData.Message> history = IntStream.iterate(100, id -> id - 1).limit(100)
                .mapToObj(id -> message(id, body)).toList();
        when(mapper.history(3L, Long.MAX_VALUE, 50)).thenReturn(history.subList(0, 50));
        when(mapper.history(3L, 51L, 50)).thenReturn(history.subList(50, 100));
        when(mapper.run(anyLong())).thenAnswer(call -> previous(call.getArgument(0), question));

        List<ChatMessage> messages = load();

        assertEquals(200, messages.size());
        assertTrue(AgentBudget.estimate(messages, List.of()) > 100000);
    }

    /** 不连接数据库也校验实际 Mapper XML 的游标和完成状态过滤条件。 */
    @Test
    void historySqlUsesDescendingMessageCursorAndSuccessfulRuns() throws Exception {
        String resource = "asia/creat/mapper/AgentMessageMapper.xml";
        var config = new Configuration();
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        var statement = config.getMappedStatement("asia.creat.mapper.AgentMessageMapper.history");
        var bound = statement.getBoundSql(Map.of("sessionId", 3L, "beforeId", 51L, "limit", 50));
        String sql = bound.getSql().replaceAll("\\s+", " ");
        assertTrue(sql.contains("m.id < ?"));
        assertTrue(sql.contains("m.role = 'ASSISTANT'"));
        assertTrue(sql.contains("r.status = 'SUCCEEDED'"));
        assertTrue(sql.contains("ORDER BY m.id DESC LIMIT ?"));
        assertEquals(List.of("sessionId", "beforeId", "limit"),
                bound.getParameterMappings().stream().map(mapping -> mapping.getProperty()).toList());
    }

    private String decodedAnswer(ChatMessage message) {
        var node = json.object(((AiMessage) message).text(), Set.of("answer", "citations"));
        assertTrue(node.path("citations").isArray());
        assertTrue(node.path("citations").isEmpty());
        return node.path("answer").asText();
    }

    /** 用已授权运行的参数验证历史装配，不启动线程或模型。 */
    private List<ChatMessage> load() {
        return loadWithin(1_000_000);
    }

    private List<ChatMessage> loadWithin(int limit) {
        AgentData.Run run = new AgentData.Run();
        run.setSessionId(3L);
        run.setSpaceId(1L);
        run.setMaxInputTokens(limit);

        List<ChatMessage> messages = new ArrayList<>(List.of(SystemMessage.from("system")));
        ReflectionTestUtils.invokeMethod(worker, "history", run, new LoginUser(7L, "test"),
                new AgentTools.State(), messages, UserMessage.from("current"), List.of());
        return new ArrayList<>(messages.subList(1, messages.size()));
    }

    /** 创建已完成的助手历史消息。 */
    private AgentData.Message message(long runId, String body) {
        AgentData.Message message = new AgentData.Message();
        message.setId(runId);
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
