package asia.creat.teamdocsbackend.agent;

import asia.creat.agent.*;
import asia.creat.agent.AgentData.*;
import asia.creat.agent.AgentBudget.InputEstimate;
import asia.creat.agent.AgentRepository;
import asia.creat.entity.Document;
import asia.creat.entity.Space;
import asia.creat.entity.ParseStatus;
import asia.creat.mapper.SpaceMapper;
import asia.creat.mapper.DocumentMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import asia.creat.memory.UserMemoryData.Item;
import asia.creat.security.LoginUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserMemoryBudgetTest {
    private final AgentRepository mapper = mock(AgentRepository.class);
    private final AgentTools tools = mock(AgentTools.class);
    private final AgentWorker worker = new AgentWorker(mapper, null, tools, null,
            new AgentJson(new ObjectMapper()), null, null, null, null, null, null, false, null, null, null, null, null);

    /** 范围规则不能伪装成空间名称或描述，且不能带入范围外的文档。 */
    @Test
    void imageBytesKeepTheirConservativeAllowance() {
        var plain = List.<ChatMessage>of(UserMessage.from("read"));
        var attached = List.<ChatMessage>of(new AttachmentMessage("read", List.of(
                new AttachmentMessage.Part("a.png", "image/png", "x".repeat(10000)))));
        assertTrue(AgentBudget.estimate(attached, List.of()) >= AgentBudget.estimate(plain, List.of()) + 10000);
    }

    @Test
    void scopedContextPreservesRealSpaceMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Document.class);
        var spaces = mock(SpaceMapper.class);
        var documents = mock(DocumentMapper.class);
        Space space = new Space();
        space.setName("研发资料库");
        space.setDescription("团队技术资料");
        when(spaces.selectById(1L)).thenReturn(space);
        Document selected = Document.builder().id(10L).spaceId(1L).name("选中文档")
                .parseVersion(1).parseStatus(ParseStatus.READY).build();
        when(documents.selectById(10L)).thenReturn(selected);
        var contextWorker = new AgentWorker(mapper, null, tools, null, new AgentJson(new ObjectMapper()),
                null, null, null, null, spaces, documents, false, null, null, null, null, null);
        var state = new AgentTools.State();
        ReflectionTestUtils.setField(state, "scopeIds", List.of(10L));
        String context = ReflectionTestUtils.invokeMethod(contextWorker, "buildSpaceContext", 1L, state);
        assertTrue(context.contains("- 空间名称: 研发资料库"));
        assertTrue(context.contains("- 空间描述: 团队技术资料"));
        assertFalse(context.contains("空间名称: 限定文档范围"));
        assertTrue(context.contains("执行约束，不是空间属性"));
        assertTrue(context.contains("选中文档"));
        ReflectionTestUtils.setField(state, "scopeIds", List.of());
        String empty = ReflectionTestUtils.invokeMethod(contextWorker, "buildSpaceContext", 1L, state);
        assertTrue(empty.contains("本次选定范围内没有文档"));
        assertFalse(empty.contains("当前空间暂未上传"));
    }

    @Test
    void optionalMemoryIsRemovedOnlyWhenAvailableInputIsInsufficient() {
        UserMessage question = UserMessage.from("hello");
        String base = "base";
        int fixed = AgentBudget.estimate(List.of(SystemMessage.from(base), question), List.of());
        int max = fixed + 20;
        SystemMessage system = ReflectionTestUtils.invokeMethod(AgentWorker.class, "memorySystemMessage", base,
                List.of(new Item("occupation", "职".repeat(160), 1L, "now")), question, List.of(), max);
        assertEquals(base, system.text());
        assertTrue(AgentBudget.estimate(List.of(system, question), List.of()) <= max);
    }

    @Test
    void fixedContextIsCheckedBeforeLoadingHistory() {
        var system = SystemMessage.from("x".repeat(2500));
        int fixed = AgentBudget.estimate(List.of(system, UserMessage.from("current")), List.of());
        Run run = Run.builder().maxInputTokens(fixed - 1).build();
        var messages = new ArrayList<ChatMessage>(List.of(system));
        AgentFailure error = assertThrows(AgentFailure.class, () -> history(run, messages));
        assertEquals("CONTEXT_LIMIT", error.code());
    }

    @Test
    void initialHistoryCanUseMoreThanTwoThirdsOfAvailableInput() {
        var system = SystemMessage.from("system");
        var question = UserMessage.from("current");
        var previousQuestion = UserMessage.from("Q".repeat(400));
        var answer = AiMessage.from(new AgentJson(new ObjectMapper()).write(
                Map.of("answer", "A".repeat(900), "citations", List.of())));
        int limit = AgentBudget.estimate(List.of(system, previousQuestion, answer,
                previousQuestion, answer, previousQuestion, answer, question), List.of());
        Run run = Run.builder().id(8L).sessionId(3L).spaceId(1L).maxInputTokens(limit).build();
        Message message = Message.builder().id(1L).runId(1L).body("A".repeat(900)).dependencies("[]").build();
        when(mapper.history(3L, Long.MAX_VALUE, 50)).thenReturn(List.of(message, message, message));
        when(mapper.run(1L)).thenReturn(Run.builder().question(previousQuestion.singleText()).build());
        var messages = new ArrayList<ChatMessage>(List.of(system));

        assertEquals(3, history(run, messages));
        messages.add(question);
        assertEquals(limit, AgentBudget.estimate(messages, List.of()));
    }

    @Test
    void laterRoundsOnlyTrimWholeHistoricalPairsAndPreserveTheToolChain() {
        var tool = ToolExecutionRequest.builder().id("t").name("search_documents").arguments("{}").build();
        var current = UserMessage.from("current");
        var call = AiMessage.from(tool);
        var result = ToolExecutionResultMessage.from(tool, "evidence");
        var messages = new ArrayList<ChatMessage>(List.of(SystemMessage.from("system"),
                UserMessage.from("x".repeat(3000)), AiMessage.from("old answer"), current, call, result));
        int limit = AgentBudget.estimate(List.of(SystemMessage.from("system"), current, call, result), List.of());
        int left = ReflectionTestUtils.invokeMethod(worker, "trimHistoryToBudget", messages, 1, limit, List.of(), new InputEstimate());
        assertEquals(0, left);
        assertEquals(List.of(SystemMessage.from("system"), current, call, result), messages);
        assertThrows(AgentFailure.class, () -> ReflectionTestUtils.invokeMethod(worker,
                "trimHistoryToBudget", messages, 0, 10, List.of(), new InputEstimate()));
    }

    @Test
    void calibratedLaterRoundTrimsOnlyTheOldestPair() {
        var system = SystemMessage.from("system");
        var current = UserMessage.from("current");
        var tool = ToolExecutionRequest.builder().id("t").name("read_document_chunks").arguments("{}").build();
        var call = AiMessage.from(tool);
        var result = ToolExecutionResultMessage.from(tool, "source evidence");
        var recentQuestion = UserMessage.from("recent question");
        var recentAnswer = AiMessage.from("recent answer");
        var kept = List.<ChatMessage>of(system, recentQuestion, recentAnswer, current, call, result);
        var messages = new ArrayList<ChatMessage>(List.of(system, UserMessage.from("old".repeat(1000)),
                AiMessage.from("old answer"), recentQuestion, recentAnswer, current, call, result));
        int raw = AgentBudget.estimate(messages, List.of());
        var estimate = new InputEstimate();
        estimate.observe(raw, raw * 2);
        int limit = AgentBudget.estimate(kept, List.of()) * 2;

        int left = ReflectionTestUtils.invokeMethod(worker, "trimHistoryToBudget", messages, 2, limit, List.of(), estimate);

        assertEquals(1, left);
        assertEquals(kept, messages);
        assertEquals(limit, estimate.estimate(messages, List.of()));
    }

    private int history(Run run, List<ChatMessage> messages) {
        return ReflectionTestUtils.invokeMethod(worker, "history", run, new LoginUser(7L, "test"),
                new AgentTools.State(), messages, UserMessage.from("current"), List.of());
    }
}
