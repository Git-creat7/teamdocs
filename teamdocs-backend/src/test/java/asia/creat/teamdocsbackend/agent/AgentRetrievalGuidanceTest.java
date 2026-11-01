package asia.creat.teamdocsbackend.agent;

import asia.creat.agent.AgentBudget;
import asia.creat.agent.AgentData.Run;
import asia.creat.agent.AgentEventHub;
import asia.creat.agent.AgentFailure;
import asia.creat.agent.AgentJson;
import asia.creat.agent.AgentRepository;
import asia.creat.agent.AgentStore;
import asia.creat.agent.AgentTools;
import asia.creat.agent.AgentWorker;
import asia.creat.config.AgentProperties;
import asia.creat.entity.Document;
import asia.creat.entity.ParseStatus;
import asia.creat.mapper.AgentMapper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.mapper.SpaceMapper;
import asia.creat.security.LoginUser;
import asia.creat.vo.ChunkHitVO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentRetrievalGuidanceTest {
    private static final LoginUser USER = new LoginUser(7L, "reader");

    @BeforeAll
    static void initializeTables() {
        for (Class<?> type : List.of(Run.class, Document.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
        }
    }

    /** 超过建议次数后仍能返回完整回答，进度只更新首条系统消息。 */
    @Test
    void continuesPastSuggestedCountsAndFinishesWithoutBreakingMessageOrder() {
        AgentRepository repository = mock(AgentRepository.class);
        AgentStore store = mock(AgentStore.class);
        AgentTools tools = mock(AgentTools.class);
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        ObjectProvider<ChatLanguageModel> provider = provider(model);
        Run run = run();
        when(repository.claim(eq(1L), anyLong())).thenReturn(true);
        when(repository.run(1L)).thenReturn(run);
        when(repository.lockRun(1L)).thenReturn(run);
        when(repository.lockUser(7L)).thenReturn(7L);
        when(repository.nextModel(eq(1L), anyLong())).thenAnswer(call -> {
            run.setModelCalls(run.getModelCalls() + 1);
            return true;
        });
        when(repository.nextTool(eq(1L), anyLong())).thenAnswer(call -> {
            run.setToolCalls(run.getToolCalls() + 1);
            return true;
        });
        when(tools.specifications()).thenReturn(AgentTools.defaultSpecifications());
        when(tools.execute(eq(1L), eq(USER), any(), any()))
                .thenReturn(new AgentTools.Result(Map.of("documents", List.of()), 0));

        AtomicInteger calls = new AtomicInteger();
        List<String> guidance = new ArrayList<>();
        when(model.generate(anyList(), anyList())).thenAnswer(call -> {
            List<ChatMessage> messages = call.getArgument(0);
            assertInstanceOf(SystemMessage.class, messages.get(0));
            assertEquals(1, messages.stream().filter(SystemMessage.class::isInstance).count());
            assertEquals(1, messages.stream().filter(UserMessage.class::isInstance).count());
            guidance.add(((SystemMessage) messages.get(0)).text());
            for (int i = 1; i < messages.size(); i++) {
                if (messages.get(i) instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                    assertInstanceOf(ToolExecutionResultMessage.class, messages.get(i + 1));
                }
            }
            int sequence = calls.incrementAndGet();
            if (sequence <= 9) {
                assertFalse(((List<?>) call.getArgument(1)).isEmpty());
                return Response.from(AiMessage.from(ToolExecutionRequest.builder().id("tool-" + sequence)
                        .name("search_documents").arguments("{\"keyword\":\"资料\"}").build()));
            }
            return Response.from(AiMessage.from("{\"answer\":\"基于现有资料的完整回答\",\"citations\":[]}"));
        });

        var executor = Executors.newSingleThreadExecutor();
        try {
            var worker = new AgentWorker(repository, store, tools, new AgentBudget(repository, properties()),
                    new AgentJson(new ObjectMapper()), provider, mock(AgentEventHub.class), null, executor,
                    mock(SpaceMapper.class), mock(DocumentMapper.class), false, provider(null), provider(null),
                    null, null, null);
            ReflectionTestUtils.invokeMethod(worker, "execute", 1L, USER);

            assertEquals(10, run.getModelCalls());
            assertEquals(9, run.getToolCalls());
            assertTrue(guidance.get(0).contains("建议最多"));
            assertTrue(guidance.get(9).contains("已调用工具 9 次"));
            assertTrue(guidance.get(9).contains("已有资料"));
            verify(store).finish(eq(run), eq("SUCCEEDED"), isNull(), eq("基于现有资料的完整回答"), anyList(), anyList());
            verify(repository, never()).endActive(anyLong(), anyString(), anyString());
        } finally {
            executor.shutdownNow();
        }
    }

    /** SQL 仍检查运行状态和截止时间，但调用计数不再阻止下一次请求。 */
    @Test
    void countersRemainAtomicWithoutCountPredicates() {
        AgentMapper mapper = mock(AgentMapper.class);
        AgentRepository repository = new AgentRepository(null, null, null, null, null);
        ReflectionTestUtils.setField(repository, "baseMapper", mapper);
        when(mapper.update(isNull(), any())).thenReturn(1);

        assertTrue(repository.nextTool(1L, 1000));
        assertTrue(repository.nextModel(1L, 1000));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<Run>> updates = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, times(2)).update(isNull(), updates.capture());
        for (var update : updates.getAllValues()) {
            String sql = update.getSqlSegment();
            assertTrue(sql.contains("status"));
            assertTrue(sql.contains("deadline_ms"));
            assertFalse(sql.contains("max_tool_calls"));
            assertFalse(sql.contains("max_model_calls"));
        }
    }

    /** 来源保留实际编号，不因读到第25个片段而丢弃整次回答。 */
    @Test
    void moreThanTwentyFourReadChunksRemainAvailableAsSources() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        Document document = Document.builder().id(10L).spaceId(1L).name("说明.txt")
                .parseStatus(ParseStatus.READY).parseVersion(1).build();
        when(mapper.selectById(10L)).thenReturn(document);
        var tools = new AgentTools(null, null, mapper, null, new AgentProperties(), new AgentJson(new ObjectMapper()), null);
        var state = new AgentTools.State();
        List<ChunkHitVO> hits = new ArrayList<>();
        for (long id = 1; id <= 25; id++) {
            var hit = new ChunkHitVO();
            hit.setDocumentId(10L);
            hit.setChunkId(id);
            hit.setParseVersion(1);
            hit.setChunkIndex((int) id - 1);
            hit.setExcerpt("正文片段" + id);
            hits.add(hit);
        }

        List<?> evidence = ReflectionTestUtils.invokeMethod(tools, "evidence", 1L, hits, state);

        assertEquals(25, evidence.size());
        assertEquals("C25", state.sources().get(24).id());
        var worker = new AgentWorker(null, null, null, null, new AgentJson(new ObjectMapper()),
                null, null, null, null, null, null, false, null, null, null, null, null);
        assertNotNull(ReflectionTestUtils.invokeMethod(worker, "finalAnswer",
                "{\"answer\":\"结论[C25]\",\"citations\":[\"C25\"]}", state));
    }

    @Test
    void contextTimeoutAndCancellationAreStillHardStops() {
        AgentRepository repository = mock(AgentRepository.class);
        Run run = run();
        run.setModelCalls(20);
        when(repository.lockUser(7L)).thenReturn(7L);
        when(repository.lockRun(1L)).thenReturn(run);
        AgentBudget budget = new AgentBudget(repository, properties());

        assertEquals("CONTEXT_LIMIT", assertThrows(AgentFailure.class,
                () -> budget.startCall(run, run.getMaxInputTokens() + 1)).code());
        run.setDeadlineMs(System.currentTimeMillis() - 1);
        assertEquals("RUN_TIMEOUT", assertThrows(AgentFailure.class, () -> budget.startCall(run, 1)).code());
        run.setStatus("CANCELLED");
        assertEquals("RUN_STOPPED", assertThrows(AgentFailure.class, () -> budget.startCall(run, 1)).code());
        verify(repository, never()).nextModel(anyLong(), anyLong());
    }

    private static Run run() {
        return Run.builder().id(1L).spaceId(1L).userId(7L).sessionId(3L).status("RUNNING").question("查找资料")
                .maxModelCalls(2).maxToolCalls(2).maxInputTokens(100000)
                .deadlineMs(System.currentTimeMillis() + 60000).build();
    }

    private static AgentProperties properties() {
        var properties = new AgentProperties();
        properties.setEnabled(true);
        properties.setAllowDocumentEgress(true);
        properties.setModelName("test-model");
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
