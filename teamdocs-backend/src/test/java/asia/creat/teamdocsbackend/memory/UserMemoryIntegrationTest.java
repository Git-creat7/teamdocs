package asia.creat.teamdocsbackend.memory;

import asia.creat.service.FileStorageService;
import asia.creat.agent.AttachmentContentReader;
import asia.creat.common.BucketType;
import asia.creat.security.LoginUser;
import org.springframework.mock.web.MockMultipartFile;
import java.io.ByteArrayInputStream;

import asia.creat.agent.*;
import asia.creat.agent.AgentData.*;
import asia.creat.agent.AgentRepository;
import asia.creat.config.AgentProperties;
import asia.creat.mapper.UserMemoryJobMapper;
import asia.creat.memory.*;
import asia.creat.memory.UserMemoryData.*;
import asia.creat.model.ModelSecretCipher;
import asia.creat.model.UserModelData;
import asia.creat.model.UserModelService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringJUnitConfig(UserMemoryIntegrationTest.Config.class)
class UserMemoryIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Autowired UserMemoryService memory;
    @Autowired UserMemoryJobMapper jobs;
    @Autowired UserMemoryWorker worker;
    @Autowired UserMemoryExtractor extractor;
    @Autowired AgentStore store;
    @Autowired ChatAttachmentService attachments;
    @Autowired FileStorageService attachmentStorage;
    @Autowired UserModelService personalModels;
    @Autowired AgentRepository agents;
    @Autowired DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeAll
    static void schema() throws Exception {
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())) {
            for (String file : List.of("initUser.sql", "initSpace.sql", "agent.sql", "user_memory.sql")) {
                ScriptUtils.executeSqlScript(connection,
                        new EncodedResource(new FileSystemResource("../sql/" + file), StandardCharsets.UTF_8));
            }
        }
    }

    @BeforeEach
    void prepare() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        for (String table : List.of("agent_attachment", "user_model_config", "user_memory_job", "user_memory", "agent_message", "agent_run",
                "agent_session", "space_member", "space", "user")) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO user(id,username,password) VALUES(1,'alice','unused'),(2,'bob','unused')");
        jdbc.update("INSERT INTO space(id,name,owner_id) VALUES(20,'A',1),(21,'B',1)");
        jdbc.update("INSERT INTO space_member(space_id,user_id,role) VALUES(20,1,'OWNER'),(21,1,'OWNER'),(20,2,'MEMBER')");
        reset(extractor);
        when(extractor.canProcessJobs()).thenReturn(true);
        when(extractor.extractForRun(anyLong(), anyString(), anyList(), any())).thenAnswer(invocation -> {
            String text = invocation.getArgument(1);
            return List.of(new Candidate("code_language", text.contains("Python") ? "Python" : "Java", text));
        });
    }

    @Test
    void disabledByDefaultAndEnablingDoesNotReplayOldConversations() throws Exception {
        assertFalse(memory.view(1).enabled());
        success(1, 20, "我主要用 Java");
        memory.settings(1, new Settings(true, 0L));
        worker.processNext();
        assertEquals(0, countJobs());
        assertTrue(memory.contextItems(1).isEmpty());
        verify(extractor, never()).extractForRun(anyLong(), anyString(), anyList(), any());
    }

    @Test
    void successfulRunsAreRememberedAcrossSpacesWithoutLeakingToAnotherUser() {
        enable();
        Run first = success(1, 20, "我主要用 Java");
        worker.processNext();
        assertEquals("Java", memory.contextItems(1).get(0).value());
        assertEquals(first.getId(), memory.view(1).items().get(0).sourceRunId());
        assertTrue(memory.contextItems(2).isEmpty());
        success(1, 21, "我主要用 Python");
        worker.processNext();
        assertEquals(1, memory.view(1).items().size());
        assertEquals("Python", memory.contextItems(1).get(0).value());
        assertThrows(RuntimeException.class, () -> memory.delete(2, "code_language", 0));
        assertEquals("Python", memory.contextItems(1).get(0).value());
    }

    @Test
    void queuedAndFailedRunsNeverTriggerExtraction() throws Exception {
        enable();
        Run run = create(1, 20, "我主要用 Java");
        worker.processNext();
        assertTrue(memory.view(1).items().isEmpty());
        agents.claim(run.getId(), System.currentTimeMillis());
        store.finish(run, "FAILED", "MODEL_UNAVAILABLE", "", List.of(), List.of());
        worker.processNext();
        worker.cleanup();
        verify(extractor, never()).extractForRun(anyLong(), anyString(), anyList(), any());
        assertEquals("DISCARDED", status(run));
    }

    @Test
    void deletionAndDisableFenceInFlightJobsEvenAfterReenable() {
        enable();
        Run run = success(1, 20, "我主要用 Java");
        Job claimed = claim(run);
        View cleared = memory.clear(1, 1);
        memory.apply(claimed, List.of(new Candidate("code_language", "Java", run.getQuestion())));
        assertTrue(memory.view(1).items().isEmpty());
        View disabled = memory.settings(1, new Settings(false, cleared.version()));
        memory.settings(1, new Settings(true, disabled.version()));
        memory.apply(claimed, List.of(new Candidate("code_language", "Java", run.getQuestion())));
        assertTrue(memory.view(1).items().isEmpty());
        assertEquals("DISCARDED", status(run));
    }

    @Test
    void manualCorrectionWinsOverOlderPendingTasksAndUsesOptimisticUserVersion() {
        enable();
        success(1, 20, "我主要用 Java");
        worker.processNext();
        Run old = success(1, 20, "我主要用 Python");
        Job claimed = claim(old);
        View edited = memory.edit(1, "code_language", new Edit("Rust", 1L));
        memory.apply(claimed, List.of(new Candidate("code_language", "Python", old.getQuestion())));
        assertEquals("Rust", memory.contextItems(1).get(0).value());
        assertThrows(RuntimeException.class, () -> memory.clear(1, 1));
        memory.delete(1, "code_language", edited.version());
        assertTrue(memory.contextItems(1).isEmpty());
    }

    @Test
    void disabledMemoryIsRetainedButNotReadOrUpdated() {
        enable();
        success(1, 20, "我主要用 Java");
        worker.processNext();
        memory.settings(1, new Settings(false, 1L));
        assertEquals(1, memory.view(1).items().size());
        assertTrue(memory.contextItems(1).isEmpty());
        success(1, 20, "我主要用 Python");
        worker.processNext();
        assertEquals("Java", memory.view(1).items().get(0).value());
    }

    @Test
    void expiredLeaseIsReclaimedButOldOwnerCannotCommit() {
        enable();
        Run run = success(1, 20, "我主要用 Java");
        Job old = claim(run);
        jdbc.update("UPDATE user_memory_job SET lease_until_ms=0 WHERE run_id=?", run.getId());
        Job current = claim(run);
        var candidates = List.of(new Candidate("code_language", "Java", run.getQuestion()));
        memory.apply(old, candidates);
        assertTrue(memory.view(1).items().isEmpty());
        memory.apply(current, candidates);
        assertEquals("Java", memory.view(1).items().get(0).value());
        assertEquals("DONE", status(run));
    }

    @Test
    void repeatedFailureIsBoundedAndNeverChangesTheSuccessfulAnswer() throws Exception {
        enable();
        Run run = success(1, 20, "我主要用 Java");
        when(extractor.extractForRun(anyLong(), anyString(), anyList(), any())).thenThrow(new IllegalStateException("test"));
        for (int i = 0; i < 3; i++) {
            jdbc.update("UPDATE user_memory_job SET next_attempt_ms=0 WHERE run_id=?", run.getId());
            worker.processNext();
        }
        assertEquals("FAILED", status(run));
        assertEquals("SUCCEEDED", agents.run(run.getId()).getStatus());
        assertEquals("answer", agents.answer(run.getId()).getBody());
    }

    @Test
    void sourceDeletionStopsExtractionWithoutErasingAlreadySavedMemory() {
        enable();
        Run saved = success(1, 20, "我主要用 Java");
        worker.processNext();
        Run pending = success(1, 20, "我主要用 Python");
        Job claimed = claim(pending);
        jdbc.update("DELETE FROM agent_run WHERE id IN (?,?)", saved.getId(), pending.getId());
        memory.apply(claimed, List.of(new Candidate("code_language", "Python", pending.getQuestion())));
        worker.cleanup();
        assertEquals("Java", memory.view(1).items().get(0).value());
        assertEquals("DISCARDED", status(pending));
    }

    @Test
    void registeringAnIdempotentRunDoesNotDuplicateTheMemoryJob() {
        enable();
        long session = store.createSession(20L, 1L, "test").getId();
        NewRun input = new NewRun("same", "我主要用 Java");
        Run first = store.createRun(20L, session, 1L, input).run();
        Run second = store.createRun(20L, session, 1L, input).run();
        assertEquals(first.getId(), second.getId());
        assertEquals(1, countJobs());
    }

    @Test
    void personalModelSnapshotIsPersistedAndRemainsIsolatedAfterEditingSettings() {
        personalModels.save(1, new UserModelData.Edit(true, 0L,
                "https://api.example.com/v1", "personal-a", "first-private-key"));
        Run run = success(1, 20, "我主要用 Java");
        Run stored = agents.run(run.getId());
        assertEquals("personal-a", stored.getModelName());
        assertNotNull(stored.getModelConfigCiphertext());
        assertFalse(stored.getModelConfigCiphertext().contains("first-private-key"));
        personalModels.save(1, new UserModelData.Edit(true, 1L,
                "https://api.example.com/v1", "personal-b", "second-private-key"));
        assertEquals("first-private-key", personalModels.credentials(stored).apiKey());
        assertEquals("personal-a", personalModels.credentials(stored).modelName());
        assertFalse(personalModels.view(2).hasKey());
        personalModels.disable(1, 2);
        Run system = success(1, 20, "hello");
        assertNull(system.getModelConfigCiphertext());
        assertEquals("test", system.getModelName());
    }

    @Test
    void attachmentsArePrivateBoundToOneRunAndCleanedAfterSessionDeletion() throws Exception {
        byte[] bytes = "{\"private\":true}".getBytes(StandardCharsets.UTF_8);
        var upload = attachments.upload(20L, new MockMultipartFile("file", "data.json", "application/json", bytes),
                new LoginUser(1L, "alice"));
        long session = store.createSession(20L, 1L, "files").getId();
        var input = new NewRun("attachment-run", "read", null, null, null, List.of(upload.id()));
        var run = store.createRun(20L, session, 1L, input).run();
        assertTrue(attachments.has(run.getId()));
        assertEquals(1, attachments.list(20L, run.getId(), new LoginUser(1L, "alice")).size());
        assertThrows(RuntimeException.class, () -> attachments.read(20L, upload.id(), new LoginUser(2L, "bob")));
        assertThrows(RuntimeException.class, () -> attachments.remove(20L, upload.id(), new LoginUser(1L, "alice")));
        assertEquals(run.getId(), store.createRun(20L, session, 1L, input).run().getId());
        when(attachmentStorage.open(eq(BucketType.PRIVATE), anyString())).thenReturn(new ByteArrayInputStream(bytes));
        assertEquals("text/plain", attachments.parts(run).get(0).mime());
        jdbc.update("DELETE FROM agent_run WHERE id=?", run.getId());
        attachments.cleanup();
        assertNull(attachments.getById(upload.id()));
        verify(attachmentStorage).delete(eq(BucketType.PRIVATE), contains(upload.id()));
    }

    private void enable() { memory.settings(1, new Settings(true, 0L)); }

    private Run create(long user, long space, String question) {
        long session = store.createSession(space, user, "memory test").getId();
        return store.createRun(space, session, user, new NewRun(UUID.randomUUID().toString(), question)).run();
    }

    private Run success(long user, long space, String question) {
        Run run = create(user, space, question);
        assertTrue(agents.claim(run.getId(), System.currentTimeMillis()));
        assertTrue(store.finish(run, "SUCCEEDED", null, "answer", List.of(), List.of()));
        return run;
    }

    private Job claim(Run run) {
        long now = System.currentTimeMillis();
        String token = UUID.randomUUID().toString();
        assertTrue(jobs.claim(run.getId(), now, now + 30_000, token));
        return jobs.current(run.getId(), token, now);
    }

    private int countJobs() { return jdbc.queryForObject("SELECT COUNT(*) FROM user_memory_job", Integer.class); }

    private String status(Run run) {
        return jdbc.queryForObject("SELECT status FROM user_memory_job WHERE run_id=?", String.class, run.getId());
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    @MapperScan("asia.creat.mapper")
    @Import({AttachmentContentReader.class, ChatAttachmentService.class, UserModelService.class, AgentRepository.class, UserMemoryService.class, UserMemoryWorker.class, AgentStore.class, AgentJson.class})
    static class Config {
        @Bean DataSource dataSource() { return new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()); }

        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean();
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            factory.setDataSource(source);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:/asia/creat/mapper/*.xml"));
            return factory.getObject();
        }

        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean ObjectMapper json() { return new ObjectMapper(); }
        @Bean FileStorageService storage() { return mock(FileStorageService.class); }
        @Bean AgentEventHub events() { return mock(AgentEventHub.class); }
        @Bean UserMemoryExtractor extractor() { return mock(UserMemoryExtractor.class); }

        @Bean ModelSecretCipher modelCipher() {
            return new ModelSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
        }

        @Bean AgentProperties properties() {
            AgentProperties p = new AgentProperties();
            p.setEnabled(true);
            p.setAllowDocumentEgress(true);
            p.setModelName("test");
            return p;
        }
    }
}
