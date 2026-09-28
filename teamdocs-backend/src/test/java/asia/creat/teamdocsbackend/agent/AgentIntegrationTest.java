package asia.creat.teamdocsbackend.agent;

import asia.creat.agent.*;
import asia.creat.agent.AgentData.*;
import asia.creat.aspect.SpaceRoleAspect;
import asia.creat.common.exception.BusinessException;
import asia.creat.config.AgentModelConfiguration;
import asia.creat.config.AgentProperties;
import asia.creat.config.MpConfig;
import asia.creat.config.RetrievalProperties;
import asia.creat.dto.PageQuery;
import asia.creat.helper.ResourcePermissionHelper;
import asia.creat.mapper.AgentMapper;
import asia.creat.security.LoginUser;
import asia.creat.service.*;
import asia.creat.service.impl.DocumentServiceImpl;
import asia.creat.service.impl.DocumentChunkQueryServiceImpl;
import asia.creat.service.impl.NoopChunkIndex;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import io.github.cdimascio.dotenv.Dotenv;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Testcontainers
@SpringJUnitConfig(AgentIntegrationTest.Config.class)
class AgentIntegrationTest {
    private static final LoginUser USER = new LoginUser(7L, "alice");
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withCommand("--ngram-token-size=2", "--innodb-ft-enable-stopword=OFF");
    @Autowired AgentService service;
    @Autowired AgentStore store;
    @Autowired AgentMapper mapper;
    @Autowired AgentWorker worker;
    @Autowired AgentBudget budget;
    @Autowired AgentProperties properties;
    @Autowired ScriptedModel model;
    @Autowired DataSource dataSource;
    @Autowired DocumentService documents;
    @Autowired @Qualifier("agentWorkerExecutor") ExecutorService executor;
    @Autowired @Qualifier("agentModelExecutor") ExecutorService modelExecutor;
    private JdbcTemplate jdbc;

    @BeforeAll
    static void initialize() throws Exception {
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())) {
            for (String script : List.of("initUser.sql", "initSpace.sql", "initDocument.sql", "fulltext_index.sql", "document_content.sql", "agent.sql")) {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(new FileSystemResource("../sql/" + script), StandardCharsets.UTF_8));
            }
        }
    }

    @BeforeEach
    void prepare() throws Exception {
        idle();
        jdbc = new JdbcTemplate(dataSource);
        for (String table : List.of("agent_model_call", "agent_tool_call", "agent_message", "agent_run", "agent_session", "document_content", "document", "space_member", "space", "user"))
            jdbc.update("DELETE FROM " + table);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(new ClassPathResource("retrieval-samples.sql"), StandardCharsets.UTF_8));
        }
        jdbc.update("INSERT INTO user(id,username,password) VALUES(7,'alice','unused'),(8,'bob','unused')");
        jdbc.update("INSERT INTO space_member(space_id,user_id,role) VALUES(1,7,'OWNER'),(1,8,'MEMBER'),(2,7,'OWNER')");
        properties.setEnabled(true); properties.setAllowDocumentEgress(true); properties.setModelName("scripted");
        properties.setInputPricePerMillion(BigDecimal.ONE); properties.setOutputPricePerMillion(BigDecimal.ONE);
        properties.setDailyBudget(BigDecimal.TEN); properties.setMaxModelCalls(6); properties.setMaxToolCalls(8);
        properties.setMaxInputTokens(16000); properties.setMaxOutputTokens(1024); properties.setRunTimeoutSeconds(10);
        model.reset((messages, specs) -> model.calls.get() == 1 ? tool("read_document_chunks", "{\"documentId\":10}")
                : answer("先备份数据库，再执行迁移。[C1]", "C1"));
        worker.recoverInterrupted();
    }

    @AfterEach void after() throws Exception { idle(); }

    @Test
    void retrievesThenAnswersWithReferencesAndKnownCost() throws Exception {
        RunView run = waitRun(submit("read"));
        assertEquals("SUCCEEDED", run.status(), run.errorCode());
        assertEquals(2, run.modelCalls()); assertEquals(1, run.toolCalls());
        assertFalse(run.answer().masked()); assertEquals(1, run.answer().citations().size());
        assertEquals("/preview/1/10", run.answer().citations().get(0).url());
        assertEquals(20, run.inputTokens()); assertEquals(40, run.outputTokens()); assertFalse(run.usageUnknown());
        assertEquals(0, new BigDecimal("0.00006000").compareTo(run.chargedCost()));
        assertFalse(run.tools().get(0).getArgumentSummary().contains("documentId"));
        assertEquals(2, service.messages(1L, run.sessionId(), new PageQuery(), USER).getTotal());
    }

    @Test
    void modelCanChooseAnotherToolBasedOnFirstResult() throws Exception {
        model.reset((messages, specs) -> switch (model.calls.get()) {
            case 1 -> tool("search_documents", "{\"keyword\":\"上线\"}");
            case 2 -> tool("search_document_chunks", "{\"keyword\":\"备份\",\"documentId\":10}");
            default -> answer("备份完成后执行迁移。[C1]", "C1");
        });
        RunView run = waitRun(submit("chain"));
        assertEquals("SUCCEEDED", run.status(), run.errorCode());
        assertEquals(3, model.calls.get()); assertEquals(2, run.tools().size());
        String context = ChatMessageSerializer.messagesToJson(model.received.get(2));
        assertTrue(context.contains("sourceId"));
        assertFalse(context.contains("filePath")); assertFalse(context.contains("k/10"));
    }

    @Test
    void multipleDocumentsCanBeReadAndCitedInOneRun() throws Exception {
        model.reset((messages, specs) -> model.calls.get() == 1 ? response(AiMessage.from(List.of(
                request("multi-1", "read_document_chunks", "{\"documentId\":10}"),
                request("multi-2", "read_document_chunks", "{\"documentId\":16}"))))
                : answer("上线前备份数据库[C1]；部署前确认网关限流[C3]。", "C1", "C3"));
        RunView run = waitRun(submit("multiple-documents"));
        assertEquals("SUCCEEDED", run.status(), run.errorCode());
        assertEquals(Set.of(10L, 16L), new java.util.HashSet<>(run.answer().citations().stream().map(Citation::documentId).toList()));
        assertEquals(2, run.toolCalls());
    }

    @Test
    void concurrentDuplicateRequestsReturnSameRunAndConflictingContentIsRejected() throws Exception {
        long session = session();
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Callable<Long> request = () -> service.submit(1L, session, new NewRun("same-key", "如何上线"), USER);
            var first = callers.submit(request); var second = callers.submit(request);
            long id = first.get(10, TimeUnit.SECONDS);
            assertEquals(id, second.get(10, TimeUnit.SECONDS));
            assertEquals("SUCCEEDED", waitRun(id).status());
            assertEquals(2, model.calls.get());
            assertEquals(id, service.submit(1L, session, new NewRun("same-key", "如何上线"), USER));
            assertThrows(BusinessException.class, () -> service.submit(1L, session, new NewRun("same-key", "其他问题"), USER));
        } finally { callers.shutdownNow(); }
    }

    @Test
    void sessionAndRunMustBelongToPathSpaceAndCurrentUser() throws Exception {
        long session = session();
        assertThrows(BusinessException.class, () -> service.submit(2L, session, new NewRun("foreign", "问题"), USER));
        assertThrows(BusinessException.class, () -> service.messages(1L, session, new PageQuery(), new LoginUser(8L, "bob")));
        long run = service.submit(1L, session, new NewRun("owned", "问题"), USER);
        waitRun(run);
        assertThrows(BusinessException.class, () -> service.run(1L, run, new LoginUser(8L, "bob")));
        assertThrows(BusinessException.class, () -> service.cancel(2L, run, USER));
    }

    @Test
    void onlyOneRunPerUserEvenAcrossSessionsAndCancellationPreventsPublication() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        model.reset((messages, specs) -> {
            entered.countDown(); awaitLatch(release);
            return tool("read_document_chunks", "{\"documentId\":10}");
        });
        long id = submit("cancel");
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            long otherSession = session();
            assertThrows(BusinessException.class, () -> service.submit(1L, otherSession, new NewRun("parallel", "问题"), USER));
            assertEquals("CANCELLED", service.cancel(1L, id, USER).status());
        } finally { release.countDown(); }
        idle();
        assertEquals("CANCELLED", mapper.run(id).getStatus());
        assertNull(mapper.answer(id)); assertEquals(1, model.calls.get()); assertTrue(mapper.traces(id).isEmpty());
    }

    @Test
    void toolArgumentsCannotSupplyIdentityOrReadAnotherSpace() throws Exception {
        for (String args : List.of("{\"documentId\":10,\"spaceId\":2}", "{\"documentId\":11}",
                "{\"documentId\":10,\"documentId\":11}", "{\"documentId\":10} trailing", "{\"documentId\":-1}")) {
            model.reset((messages, specs) -> tool("read_document_chunks", args));
            RunView run = waitRun(submit("invalid" + System.nanoTime()));
            assertEquals("FAILED", run.status()); assertEquals(1, model.calls.get()); assertNull(run.answer());
            assertEquals("FAILED", run.tools().get(0).getStatus());
        }
        model.reset((messages, specs) -> tool("delete_document", "{\"documentId\":10}"));
        RunView run = waitRun(submit("unknown"));
        assertEquals("TOOL_NOT_ALLOWED", run.errorCode());
        assertEquals(0, jdbc.queryForObject("SELECT deleted FROM document WHERE id=10", Integer.class));
    }

    @Test
    void missingEvidenceIsExplicitAndDoesNotInventReferences() throws Exception {
        model.reset((messages, specs) -> model.calls.get() == 1 ? tool("search_documents", "{\"keyword\":\"不存在的火星条例\"}")
                : answer("当前空间没有检索到相关资料，无法据此回答。"));
        RunView run = waitRun(submit("empty"));
        assertEquals("SUCCEEDED", run.status(), run.errorCode()); assertTrue(run.answer().citations().isEmpty());
    }

    @Test
    void invalidReferenceGetsAtMostOneBudgetedRepair() throws Exception {
        model.reset((messages, specs) -> switch (model.calls.get()) {
            case 1 -> tool("read_document_chunks", "{\"documentId\":10}");
            case 2 -> answer("错误引用[C999]", "C999");
            default -> answer("先备份[C1]", "C1");
        });
        RunView repaired = waitRun(submit("repair"));
        assertEquals("SUCCEEDED", repaired.status()); assertEquals(3, repaired.modelCalls());
        model.reset((messages, specs) -> model.calls.get() == 1 ? tool("read_document_chunks", "{\"documentId\":10}")
                : answer("错误引用[C999]", "C999"));
        RunView failed = waitRun(submit("bad-reference"));
        assertEquals("ANSWER_UNVERIFIABLE", failed.errorCode()); assertEquals(3, failed.modelCalls()); assertNull(failed.answer());
    }

    @Test
    void modelToolAndContextBudgetsStopFurtherWork() throws Exception {
        properties.setMaxModelCalls(1);
        RunView modelLimit = waitRun(submit("model-limit"));
        assertEquals("MODEL_CALL_LIMIT", modelLimit.errorCode()); assertEquals(1, model.calls.get());
        assertNotNull(modelLimit.answer());
        properties.setMaxModelCalls(6); properties.setMaxToolCalls(1);
        model.reset((messages, specs) -> response(AiMessage.from(List.of(
                request("a", "read_document_chunks", "{\"documentId\":10}"),
                request("b", "read_document_chunks", "{\"documentId\":15}")))));
        RunView toolLimit = waitRun(submit("tool-limit"));
        assertEquals("TOOL_CALL_LIMIT", toolLimit.errorCode()); assertEquals(1, toolLimit.toolCalls());
        properties.setMaxInputTokens(100);
        model.reset((messages, specs) -> { throw new AssertionError("must not call model"); });
        RunView contextLimit = waitRun(submit("context-limit"));
        assertEquals("CONTEXT_LIMIT", contextLimit.errorCode()); assertEquals(0, contextLimit.modelCalls());
    }

    @Test
    void timeoutRetainsUnknownReservationAndCannotPublishLateAnswer() throws Exception {
        properties.setRunTimeoutSeconds(1);
        model.reset((messages, specs) -> { awaitLatch(new CountDownLatch(1)); return answer("迟到的回答"); });
        RunView run = waitRun(submit("timeout"));
        assertEquals("TIMED_OUT", run.status()); assertNull(run.answer());
        assertTrue(run.usageUnknown()); assertTrue(run.chargedCost().signum() > 0);
    }

    @Test
    void missingPriceOrConsentFailsClosedAndDailyQuotaPreventsPayment() throws Exception {
        properties.setInputPricePerMillion(null);
        long session = session();
        assertThrows(BusinessException.class, () -> service.submit(1L, session, new NewRun("no-price", "问题"), USER));
        properties.setInputPricePerMillion(BigDecimal.ONE); properties.setAllowDocumentEgress(false);
        assertThrows(BusinessException.class, () -> service.submit(1L, session, new NewRun("no-egress", "问题"), USER));
        properties.setAllowDocumentEgress(true); properties.setDailyBudget(new BigDecimal("0.00000001"));
        RunView run = waitRun(submit("quota"));
        assertEquals("DAILY_BUDGET_EXCEEDED", run.errorCode()); assertEquals(0, model.calls.get());
        assertFalse(documents.searchDocuments(1L, "上线", new PageQuery(), USER).getRecords().isEmpty());
    }

    @Test
    void uncitedDependencyDeletionMasksWholeAnswerAndExcludesHistoryFromNextPrompt() throws Exception {
        model.reset((messages, specs) -> model.calls.get() == 1 ? tool("search_document_chunks", "{\"keyword\":\"上线\"}")
                : answer("unique-private-answer：先备份[C1]", "C1"));
        long session = session();
        long id = service.submit(1L, session, new NewRun("first", "unique-private-question"), USER);
        RunView first = waitRun(id); assertEquals("SUCCEEDED", first.status(), first.errorCode());
        jdbc.update("UPDATE document SET deleted=1 WHERE id=15");
        RunView masked = service.run(1L, id, USER);
        assertTrue(masked.answer().masked()); assertFalse(masked.answer().text().contains("unique-private-answer"));
        assertTrue(masked.answer().citations().isEmpty());
        model.reset((messages, specs) -> model.calls.get() == 1 ? tool("read_document_chunks", "{\"documentId\":10}")
                : answer("当前资料要求先备份[C1]", "C1"));
        assertEquals("SUCCEEDED", waitRun(service.submit(1L, session, new NewRun("followup", "当前规范是什么"), USER)).status());
        String prompt = ChatMessageSerializer.messagesToJson(model.received.get(0));
        assertFalse(prompt.contains("unique-private-answer")); assertFalse(prompt.contains("unique-private-question"));
    }

    @Test
    void sourceChangeDuringModelCallPreventsAnswerPublication() throws Exception {
        model.reset((messages, specs) -> {
            if (model.calls.get() == 1) return tool("read_document_chunks", "{\"documentId\":10}");
            jdbc.update("UPDATE document SET parse_version=parse_version+1 WHERE id=10");
            return answer("旧资料[C1]", "C1");
        });
        RunView run = waitRun(submit("changed"));
        assertEquals("SOURCE_CHANGED", run.errorCode()); assertNull(run.answer());
    }

    @Test
    void membershipRevocationStopsSubsequentToolsAndResultReads() throws Exception {
        model.reset((messages, specs) -> {
            jdbc.update("DELETE FROM space_member WHERE space_id=1 AND user_id=7");
            return tool("read_document_chunks", "{\"documentId\":10}");
        });
        long id = submit("revoke");
        await(() -> !Set.of("QUEUED", "RUNNING").contains(mapper.run(id).getStatus()));
        assertEquals("ACCESS_REVOKED", mapper.run(id).getErrorCode()); assertTrue(mapper.traces(id).isEmpty());
        assertThrows(BusinessException.class, () -> service.run(1L, id, USER));
    }

    @Test
    void restartMarksUnfinishedRunsFailedWithoutReplayingOrRefundingUnknownCalls() {
        long session = session();
        Run run = store.createRun(1L, session, USER.getUserId(), new NewRun("interrupted", "问题")).run();
        assertEquals(1, mapper.claim(run.getId(), System.currentTimeMillis()));
        budget.reserve(run, 1000);
        properties.setEnabled(false);
        worker.recoverInterrupted();
        assertEquals("PROCESS_INTERRUPTED", mapper.run(run.getId()).getErrorCode());
        assertFalse(mapper.charges(run.getId()).get(0).isUsageKnown()); assertEquals(0, model.calls.get());
    }

    @Test
    void queuedDeadlineUsesEpochTimeEvenWhenDatabaseAndJvmTimezonesDiffer() throws Exception {
        properties.setRunTimeoutSeconds(1);
        Run run = store.createRun(1L, session(), 7L, new NewRun("queued-timeout", "问题")).run();
        Thread.sleep(1200);
        worker.expire();
        assertEquals("TIMED_OUT", mapper.run(run.getId()).getStatus());
        assertEquals(0, model.calls.get());
    }

    @Test
    void quotaReservationsAreAtomicAndQuotaStorageFailureBlocksOnlyNewAiRuns() throws Exception {
        properties.setDailyBudget(new BigDecimal("0.003"));
        Run run = store.createRun(1L, session(), 7L, new NewRun("atomic", "问题")).run();
        mapper.claim(run.getId(), System.currentTimeMillis());
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> reserve = () -> {
                try { budget.reserve(run, 1000); return true; }
                catch (AgentFailure e) { assertEquals("DAILY_BUDGET_EXCEEDED", e.code()); return false; }
            };
            var first = callers.submit(reserve); var second = callers.submit(reserve);
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, mapper.charges(run.getId()).size());
            assertEquals(1, mapper.run(run.getId()).getModelCalls());
        } finally { callers.shutdownNow(); mapper.endActive(run.getId(), "FAILED", "TEST_COMPLETE"); }
        long session = session();
        jdbc.execute("RENAME TABLE agent_model_call TO agent_model_call_unavailable");
        try {
            assertThrows(RuntimeException.class, () -> service.submit(1L, session, new NewRun("unavailable", "问题"), USER));
            assertFalse(documents.searchDocuments(1L, "上线", new PageQuery(), USER).getRecords().isEmpty());
            assertEquals(0, model.calls.get());
        } finally { jdbc.execute("RENAME TABLE agent_model_call_unavailable TO agent_model_call"); }
    }

    @Test
    void historicalAnswerCarriesTransitiveDependenciesIntoFollowup() throws Exception {
        long session = session();
        model.reset((messages, specs) -> model.calls.get() == 1 ? tool("search_document_chunks", "{\"keyword\":\"上线\"}")
                : answer("historical-answer：备份[C1]", "C1"));
        long first = service.submit(1L, session, new NewRun("first", "问题一"), USER);
        assertEquals("SUCCEEDED", waitRun(first).status());
        model.reset((messages, specs) -> model.calls.get() == 1 ? tool("read_document_chunks", "{\"documentId\":10}")
                : answer("结合历史，先备份[C1]", "C1"));
        long second = service.submit(1L, session, new NewRun("second", "追问"), USER);
        assertEquals("SUCCEEDED", waitRun(second).status());
        assertTrue(ChatMessageSerializer.messagesToJson(model.received.get(0)).contains("historical-answer"));
        jdbc.update("UPDATE document SET deleted=1 WHERE id=15");
        assertTrue(service.run(1L, first, USER).answer().masked());
        assertTrue(service.run(1L, second, USER).answer().masked());
    }

    @Test
    void anomalousUsageStopsAfterRecordingActualUsage() throws Exception {
        model.reset((messages, specs) -> Response.from(AiMessage.from(List.of(request("usage", "read_document_chunks", "{\"documentId\":10}"))), new TokenUsage(10, 2000)));
        RunView run = waitRun(submit("bad-usage"));
        assertEquals("MODEL_USAGE_INVALID", run.errorCode()); assertEquals(1, run.modelCalls());
        assertFalse(run.usageUnknown()); assertEquals(2000, run.outputTokens());
        assertTrue(run.tools().isEmpty());
    }

    @Test
    @EnabledIfSystemProperty(named = "teamdocs.live-agent", matches = "true")
    void liveModelReadsOnlySyntheticFixtures() throws Exception {
        Dotenv env = Dotenv.configure().directory("..").ignoreIfMissing().load();
        AgentProperties live = new AgentProperties();
        live.setApiKey(env.get("AGENT_API_KEY")); live.setBaseUrl(env.get("AGENT_BASE_URL")); live.setModelName(env.get("AGENT_MODEL_NAME"));
        live.setTimeoutSeconds(Integer.parseInt(env.get("AGENT_TIMEOUT_SECONDS", "60"))); live.setMaxOutputTokens(1024);
        ChatLanguageModel delegate = new AgentModelConfiguration(live).chatLanguageModel();
        assertNotNull(delegate, "在线测试需要模型配置");
        properties.setModelName(live.getModelName()); properties.setRunTimeoutSeconds(90);
        model.reset((messages, specs) -> {
            long start = System.nanoTime();
            System.out.println("LIVE_AGENT_CALL_START attempt=" + model.calls.get() + " messages=" + messages.size()
                    + " estimatedInput=" + AgentBudget.estimate(messages));
            Response<AiMessage> response = delegate.generate(messages, specs);
            System.out.println("LIVE_AGENT_CALL_END elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                    + " finish=" + response.finishReason() + " tools=" + (response.content().hasToolExecutionRequests()
                    ? response.content().toolExecutionRequests().stream().map(request -> request.name()).toList() : List.of())
                    + " usage=" + response.tokenUsage());
            return response;
        });
        long session = session();
        long id = service.submit(1L, session, new NewRun("live", "请先查找上线手册，再读取正文，说明上线前和回滚时怎么处理数据库。只根据资料回答并标注引用。"), USER);
        await(() -> !Set.of("QUEUED", "RUNNING").contains(mapper.run(id).getStatus()), 100000);
        RunView run = service.run(1L, id, USER);
        assertEquals("SUCCEEDED", run.status(), run.errorCode() + " modelCalls=" + run.modelCalls() + " toolCalls=" + run.toolCalls());
        assertFalse(run.answer().citations().isEmpty()); assertTrue(run.answer().text().contains("备份"));
        assertTrue(run.modelCalls() <= 6); assertTrue(run.toolCalls() >= 2);
        assertFalse(run.usageUnknown()); assertTrue(run.inputTokens() > 0); assertTrue(run.outputTokens() > 0);
        System.out.println("LIVE_AGENT_SUCCESS calls=" + run.modelCalls() + " tools=" + run.toolCalls()
                + " input=" + run.inputTokens() + " output=" + run.outputTokens());
        long multiId = service.submit(1L, session, new NewRun("live-multi", "再查找并比较上线手册和部署说明：分别说明两份资料的要求，必须读取并引用这两份文档。"), USER);
        await(() -> !Set.of("QUEUED", "RUNNING").contains(mapper.run(multiId).getStatus()), 100000);
        RunView multi = service.run(1L, multiId, USER);
        assertEquals("SUCCEEDED", multi.status(), multi.errorCode());
        assertTrue(multi.answer().citations().stream().map(Citation::documentId).distinct().count() >= 2);
        assertFalse(multi.usageUnknown());
        System.out.println("LIVE_AGENT_MULTI_SUCCESS calls=" + multi.modelCalls() + " tools=" + multi.toolCalls()
                + " input=" + multi.inputTokens() + " output=" + multi.outputTokens());
    }

    private long session() { return service.createSession(1L, new NewSession("测试会话"), USER).getId(); }
    private long submit(String key) { return service.submit(1L, session(), new NewRun(key, "如何上线与回滚"), USER); }
    private RunView waitRun(long id) throws Exception {
        await(() -> !Set.of("QUEUED", "RUNNING").contains(mapper.run(id).getStatus()));
        return service.run(1L, id, USER);
    }
    private void idle() throws Exception {
        await(() -> ((ThreadPoolExecutor) executor).getActiveCount() == 0 && ((ThreadPoolExecutor) executor).getQueue().isEmpty()
                && ((ThreadPoolExecutor) modelExecutor).getActiveCount() == 0, 15000);
    }
    private static void await(BooleanSupplier condition) throws Exception { await(condition, 15000); }
    private static void await(BooleanSupplier condition, long millis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), "condition timed out");
    }
    private static void awaitLatch(CountDownLatch latch) {
        try { latch.await(15, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private static ToolExecutionRequest request(String id, String name, String args) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(args).build();
    }
    private static Response<AiMessage> tool(String name, String args) {
        return response(AiMessage.from(List.of(request("call-" + System.nanoTime(), name, args))));
    }
    private static Response<AiMessage> answer(String text, String... citations) {
        try {
            return response(AiMessage.from(new ObjectMapper().writeValueAsString(java.util.Map.of("answer", text, "citations", List.of(citations)))));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static Response<AiMessage> response(AiMessage message) { return Response.from(message, new TokenUsage(10, 20)); }

    static class ScriptedModel implements ChatLanguageModel {
        final AtomicInteger calls = new AtomicInteger();
        final List<List<ChatMessage>> received = new CopyOnWriteArrayList<>();
        volatile BiFunction<List<ChatMessage>, List<ToolSpecification>, Response<AiMessage>> script;
        void reset(BiFunction<List<ChatMessage>, List<ToolSpecification>, Response<AiMessage>> script) {
            this.script = script; calls.set(0); received.clear();
        }
        @Override public Response<AiMessage> generate(List<ChatMessage> messages) { return generate(messages, List.of()); }
        @Override public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> tools) {
            calls.incrementAndGet(); received.add(List.copyOf(messages)); return script.apply(messages, tools);
        }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @MapperScan("asia.creat.mapper")
    @Import({AgentService.class, AgentStore.class, AgentWorker.class, AgentTools.class, AgentBudget.class, AgentJson.class,
            AgentExecutionConfiguration.class, DocumentServiceImpl.class, DocumentChunkQueryServiceImpl.class,
            SpaceRoleAspect.class, ResourcePermissionHelper.class})
    static class Config {
        @Bean DataSource dataSource() { return new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            MybatisConfiguration configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:/asia/creat/mapper/*.xml"));
            factory.setPlugins(new MpConfig().mybatisPlusInterceptor()); return factory.getObject();
        }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) { return new DataSourceTransactionManager(dataSource); }
        @Bean AgentProperties agentProperties() { return new AgentProperties(); }
        @Bean RetrievalProperties retrievalProperties() { return new RetrievalProperties(); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); }
        @Bean ScriptedModel model() { return new ScriptedModel(); }
        @Bean ChunkIndex index() { return new NoopChunkIndex(); }
        @Bean DocumentIndexSync documentIndexSync(ChunkIndex index) { return new DocumentIndexSync(index); }
        @Bean FileStorageService storage() { return mock(FileStorageService.class); }
        @Bean DocumentContentService content() { return mock(DocumentContentService.class); }
        @Bean RecentDocumentService recent() { return mock(RecentDocumentService.class); }
    }
}
