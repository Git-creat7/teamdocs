package asia.creat.teamdocsbackend.agent;

import asia.creat.TeamdocsBackendApplication;
import asia.creat.agent.*;
import asia.creat.agent.AgentData.*;
import asia.creat.agent.AgentRepository;
import asia.creat.mapper.DocumentMapper;
import asia.creat.security.LoginUser;
import asia.creat.utils.JWTUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(classes = AgentSseIntegrationTest.Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AgentSseIntegrationTest {
    private static final LoginUser USER = new LoginUser(7L, "p4_reader");
    @Container static final MySQLContainer<?> MYSQL = mysql();
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @Container static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(System.getProperty(
            "teamdocs.test.minio-image", "ghcr.io/git-creat7/teamdocs/minio@sha256:648817f3b321ec7a2f86c594ba468fa19eff8ee3ac17a07c03acf7a8a35fda33")))
            .withEnv("MINIO_ROOT_USER", "p4-test-access").withEnv("MINIO_ROOT_PASSWORD", "p4-test-secret")
            .withEnv("MINIO_API_CORS_ALLOW_ORIGIN", "*").withCommand("server", "/data").withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JWTUtils jwt;
    @Autowired PasswordEncoder passwords;
    @Autowired AgentRepository mapper;
    @Autowired DocumentMapper documents;
    @Autowired AgentStore store;
    @Autowired AgentWorker worker;
    @Autowired AgentEventHub events;
    @Autowired AgentReasoningRegistry reasoning;
    @Autowired ObjectMapper json;
    @Autowired @Qualifier("agentEventExecutor") ExecutorService sender;
    @MockitoSpyBean AgentService service;
    @MockitoBean ChatLanguageModel model;
    private String token;

    static MySQLContainer<?> mysql() {
        MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
                .withCommand("--ngram-token-size=2", "--innodb-ft-enable-stopword=OFF");
        int index = 0;

        for (String script : List.of("initUser.sql", "initSpace.sql", "initDocument.sql", "initComment.sql", "initOperationLog.sql", "fulltext_index.sql", "document_content.sql", "agent.sql"))
            mysql.withCopyFileToContainer(MountableFile.forHostPath(Path.of("../sql", script).toAbsolutePath()), "/docker-entrypoint-initdb.d/" + (++index) + ".sql");

        return mysql;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("jwt.secret", () -> "p4-test-only-secret-with-at-least-32-bytes");
        registry.add("minio.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("minio.public-endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("minio.access-key", () -> "p4-test-access");
        registry.add("minio.secret-key", () -> "p4-test-secret");
        registry.add("minio.bucket-public", () -> "p4-public");
        registry.add("minio.bucket-private", () -> "p4-private");
        registry.add("teamdocs.elasticsearch.enabled", () -> false);
        registry.add("teamdocs.agent.enabled", () -> true);
        registry.add("teamdocs.agent.allow-document-egress", () -> true);
        registry.add("teamdocs.agent.model-name", () -> "p4-local-test-model");
        registry.add("teamdocs.agent.api-key", () -> "test-only-key");
        registry.add("teamdocs.agent.base-url", () -> "http://127.0.0.1:1/v1");
        registry.add("teamdocs.parse.scan-delay-ms", () -> "200");
    }

    @BeforeEach
    void prepare() throws Exception {
        events.close();

        for (String table : List.of("agent_model_call", "agent_tool_call", "agent_message", "agent_run", "agent_session", "document_content", "document_tag", "document", "space_member", "space", "user")) jdbc.update("DELETE FROM " + table);

        jdbc.update("INSERT INTO user(id,username,password,nickname) VALUES(7,'p4_reader',?,'验收用户'),(8,'other_reader',?,'其他用户')", passwords.encode("p4-fixture-password"), passwords.encode("unused"));
        jdbc.update("INSERT INTO space(id,name,owner_id,description) VALUES(1,'研发资料',7,'上线规范与备份记录'),(2,'项目交付',7,'交付检查与使用说明')");
        jdbc.update("INSERT INTO space_member(space_id,user_id,role) VALUES(1,7,'OWNER'),(2,7,'OWNER'),(1,8,'MEMBER')");
        token = jwt.generateJWT(Map.of("userId", 7L, "username", "p4_reader"));

        MinioClient client = MinioClient.builder().endpoint("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000))
                .credentials("p4-test-access", "p4-test-secret").build();

        for (String bucket : List.of("p4-public", "p4-private")) if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
    }

    @AfterEach void closeObservers() { events.close(); }

    @Test
    void modelStartedIsVisibleBeforeTheBlockingModelResponse() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        when(model.generate(anyList(), anyList())).thenAnswer(invocation -> {
            entered.countDown();

            if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("model test latch timed out");

            return Response.from(AiMessage.from("{\"answer\":\"你好，请问需要什么帮助？\",\"citations\":[]}"), new TokenUsage(10, 10));
        });

        Session session = service.createSession(1L, new NewSession("请求开始通知"), USER);
        Run run = store.createRun(1L, session.getId(), 7L, new NewRun("model-start-notice", "hi")).run();

        try (InputStream body = open(url(run.getId()))) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));

            assertTrue(readFrame(reader).contains("event:snapshot"));

            worker.enqueue(run.getId(), USER);

            assertTrue(entered.await(5, TimeUnit.SECONDS));

            String frame = readFrame(reader);

            if (!frame.contains("event:model_started")) frame = readFrame(reader);

            assertTrue(frame.contains("event:model_started"), frame);
            assertTrue(frame.contains("\"modelCalls\":1"));
            assertNull(mapper.answer(run.getId()), "the response must not be required to observe request start");

            release.countDown();

            assertTrue(readRest(reader).contains("run_finished"));
        } finally {
            release.countDown();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);

            while (worker.isExecuting(run.getId()) && System.nanoTime() < deadline) Thread.sleep(20);
        }
    }

    /** 不调用模型也能验证累计思考先于最终答案到达。 */
    @Test
    void reasoningSnapshotsArriveBeforeAnswerWithoutCallingModel() throws Exception {
        Run run = running();

        try (InputStream body = open(url(run.getId()))) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
            String initial = readEvent(reader, "snapshot");

            assertTrue(reasoning.publish(run, new ReasoningProgress("临时思考", null, false), List.of()));

            String first = readEvent(reader, "reasoning_updated");
            JsonNode progress = snapshot(first);

            assertEquals("RUNNING", progress.path("status").asText());
            assertEquals("临时思考", progress.path("reasoningContent").asText());
            assertTrue(progress.path("reasoningDurationMs").isNull());
            assertTrue(progress.path("answer").path("id").isNull());
            assertEquals("", progress.path("answer").path("text").asText());
            assertNull(mapper.answer(run.getId()), "reasoning must arrive before the answer row exists");
            assertEquals(1, mapper.countMessages(run.getSessionId()));

            assertTrue(reasoning.publish(run, new ReasoningProgress("临时思考，继续核对", null, true), List.of()));

            String second = readEvent(reader, "reasoning_updated");

            progress = snapshot(second);

            assertEquals("临时思考，继续核对", progress.path("reasoningContent").asText());
            assertTrue(progress.path("reasoningDurationMs").isNull());
            assertTrue(progress.path("reasoningTruncated").asBoolean());
            assertNull(mapper.answer(run.getId()));

            assertTrue(store.finish(run, "SUCCEEDED", null, "最终答案", List.of(), List.of()));

            String tail = readRest(reader);

            assertTrue(tail.contains("event:answer_ready"));
            assertTrue(tail.contains("event:run_finished"));
            assertTrue(tail.contains("最终答案"));
            assertOrderedIds(initial + first + second + tail);
            assertEquals("最终答案", mapper.answer(run.getId()).getBody());
            assertEquals(2, mapper.countMessages(run.getSessionId()));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM agent_message WHERE run_id=? AND body LIKE ?",
                    Integer.class, run.getId(), "%临时思考%"));
            verify(model, never()).generate(anyList(), anyList());
        }
    }

    /** 排队的思考通知在发送时重新核对来源，不泄露已删除资料。 */
    @Test
    void queuedReasoningIsReauthorizedBeforeSendingWithoutAnswerRow() throws Exception {
        Run run = running();

        jdbc.update("INSERT INTO document(id,space_id,name,file_path,upload_by,parse_status,parse_version) VALUES(10,1,'思考来源','unused',7,'READY',1)");

        var document = documents.selectById(10L);
        Dependency dependency = new Dependency(10L, 1, "READY", document.getName(), document.getUpdatedAt());

        try (InputStream body = open(url(run.getId()))) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));

            readEvent(reader, "snapshot");

            CountDownLatch occupied = new CountDownLatch(2), release = new CountDownLatch(1);

            for (int i = 0; i < 2; i++) sender.execute(() -> { occupied.countDown(); waitLatch(release); });

            try {
                assertTrue(occupied.await(5, TimeUnit.SECONDS));
                assertTrue(reasoning.publish(run, new ReasoningProgress("PRIVATE-REASONING", 15L, false), List.of(dependency)));

                jdbc.update("UPDATE document SET deleted=1 WHERE id=10");
            } finally { release.countDown(); }

            String frame = readEvent(reader, "reasoning_updated");
            JsonNode progress = snapshot(frame);

            assertTrue(progress.path("answer").path("masked").asBoolean());
            assertTrue(progress.path("reasoningContent").isNull());
            assertTrue(progress.path("reasoningDurationMs").isNull());
            assertFalse(frame.contains("PRIVATE-REASONING"));
            assertNull(mapper.answer(run.getId()));

            service.cancel(1L, run.getId(), USER);

            String tail = readRest(reader);

            assertTrue(tail.contains("event:run_finished"));
            assertFalse(tail.contains("PRIVATE-REASONING"));
            assertFalse(tail.contains("event:answer_ready"));
        }
    }

    @Test
    void anonymousDeletionCannotRemoveASession() throws Exception {
        Session session = service.createSession(1L, new NewSession("保留会话"), USER);
        URI endpoint = URI.create("http://127.0.0.1:" + port + "/spaces/1/agent/sessions/" + session.getId());

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(endpoint).DELETE().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode());
        assertNotNull(mapper.session(session.getId(), 1L, 7L));
    }

    @Test
    void anonymousAndForeignRequestsCannotSubscribeAndDoNotCreateHttpSessions() throws Exception {
        Run run = running();
        HttpResponse<String> anonymous = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url(run.getId())))
                .header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(401, anonymous.statusCode());

        String other = jwt.generateJWT(Map.of("userId", 8L, "username", "other_reader"));
        HttpResponse<String> foreign = HttpClient.newHttpClient().send(request(url(run.getId()), other), HttpResponse.BodyHandlers.ofString());

        assertEquals(0, json.readTree(foreign.body()).get("code").intValue());
        assertTrue(foreign.headers().firstValue("content-type").orElse("").contains("application/json"));
        assertTrue(foreign.headers().allValues("set-cookie").isEmpty());
    }

    @Test
    void completedSubscriptionReturnsTerminalSnapshotAndClosesWithAuthenticatedAsyncDispatch() throws Exception {
        Run run = running();

        store.finish(run, "SUCCEEDED", null, "已完成", List.of(), List.of());

        HttpResponse<String> response = HttpClient.newHttpClient().send(request(url(run.getId()), token), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElse("").contains("text/event-stream"));
        assertEquals("no-store", response.headers().firstValue("cache-control").orElse(""));
        assertTrue(response.headers().allValues("set-cookie").isEmpty());
        assertTrue(response.body().contains("event:snapshot"));
        assertTrue(response.body().contains("SUCCEEDED"));
        assertFalse(response.body().contains("未认证"));
    }

    @Test
    void completionDuringInitialSnapshotCannotBeMissed() throws Exception {
        Run run = running();
        CountDownLatch loaded = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        AgentService target = AopTestUtils.getUltimateTargetObject(service);

        doAnswer(invocation -> {
            Object value = invocation.callRealMethod();

            if (reads.incrementAndGet() == 2) { loaded.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); }

            return value;
        }).when(target).run(eq(1L), eq(run.getId()), any());

        CompletableFuture<HttpResponse<String>> response = HttpClient.newHttpClient().sendAsync(request(url(run.getId()), token), HttpResponse.BodyHandlers.ofString());

        try {
            assertTrue(loaded.await(5, TimeUnit.SECONDS));
            store.finish(run, "SUCCEEDED", null, "完成期间的结果", List.of(), List.of());
        } finally { release.countDown(); }

        String body = response.get(10, TimeUnit.SECONDS).body();

        assertTrue(body.contains("event:snapshot"));
        assertTrue(body.contains("event:run_finished"));
        assertTrue(body.contains("完成期间的结果"));
        assertOrderedIds(body);
    }

    @Test
    void queuedFinalNotificationIsReauthorizedAndMasksChangedSources() throws Exception {
        Run run = running();

        jdbc.update("INSERT INTO document(id,space_id,name,file_path,upload_by,parse_status,parse_version) VALUES(10,1,'测试资料','unused',7,'READY',1)");

        var document = documents.selectById(10L);
        Dependency dependency = new Dependency(10L, 1, "READY", document.getName(), document.getUpdatedAt());

        try (InputStream body = open(url(run.getId()))) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));

            assertTrue(readFrame(reader).contains("event:snapshot"));

            CountDownLatch occupied = new CountDownLatch(2), release = new CountDownLatch(1);

            for (int i = 0; i < 2; i++) sender.execute(() -> { occupied.countDown(); waitLatch(release); });

            try {
                assertTrue(occupied.await(5, TimeUnit.SECONDS));
                store.finish(run, "SUCCEEDED", null, "PRIVATE-DERIVED-ANSWER", List.of(dependency), List.of());
                jdbc.update("UPDATE document SET deleted=1 WHERE id=10");
            } finally { release.countDown(); }

            String tail = readRest(reader);

            assertTrue(tail.contains("资料已更新或不可访问"));
            assertFalse(tail.contains("PRIVATE-DERIVED-ANSWER"));
        }
    }

    @Test
    void membershipLossClosesStreamWithoutAnswerAndConnectionCountIsBounded() throws Exception {
        Run run = running();

        try (InputStream first = open(url(run.getId())); InputStream second = open(url(run.getId()))) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(first, StandardCharsets.UTF_8));

            readFrame(reader);

            HttpResponse<String> limited = HttpClient.newHttpClient().send(request(url(run.getId()), token), HttpResponse.BodyHandlers.ofString());

            assertEquals(0, json.readTree(limited.body()).get("code").intValue());

            CountDownLatch occupied = new CountDownLatch(2), release = new CountDownLatch(1);

            for (int i = 0; i < 2; i++) sender.execute(() -> { occupied.countDown(); waitLatch(release); });

            try {
                assertTrue(occupied.await(5, TimeUnit.SECONDS));
                assertTrue(reasoning.publish(run, new ReasoningProgress("PRIVATE-MEMBERSHIP-REASONING", null, false), List.of()));

                jdbc.update("DELETE FROM space_member WHERE space_id=1 AND user_id=7");
            } finally { release.countDown(); }

            String tail = readRest(reader);

            assertTrue(tail.contains("ACCESS_REVOKED"));
            assertFalse(tail.contains("PRIVATE-MEMBERSHIP-REASONING"));
            assertFalse(tail.contains("answer_ready"));
        }
    }

    @Test
    void nginxFlushesProgressBeforeCompletionAndForwardsBearerAuthentication() throws Exception {
        Run run = running();

        org.testcontainers.Testcontainers.exposeHostPorts(port);

        String config = Files.readString(Path.of("../teamdocs-frontend/nginx.conf"))
                .replace("set $backend_upstream http://backend:8080;", "")
                .replace("proxy_pass $backend_upstream;", "proxy_pass http://host.testcontainers.internal:" + port + ";");

        try (GenericContainer<?> nginx = new GenericContainer<>(System.getProperty("teamdocs.test.nginx-image", "nginx:1.27-alpine"))
                .withCopyToContainer(Transferable.of(config), "/etc/nginx/conf.d/default.conf")
                .withExposedPorts(80).waitingFor(Wait.forHttp("/healthz"))) {
            nginx.start();

            String endpoint = "http://" + nginx.getHost() + ":" + nginx.getMappedPort(80) + "/api/spaces/1/agent/runs/" + run.getId() + "/events";

            try (InputStream body = open(endpoint)) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));

                assertTrue(readFrame(reader).contains("event:snapshot"));

                Trace trace = new Trace();
                trace.setRunId(run.getId());
                trace.setSequence(1);
                trace.setToolName("search_documents");
                trace.setArgumentSummary("argumentChars=2");
                trace.setResultSummary("records=0");
                trace.setStatus("RUNNING");

                mapper.insertTrace(trace);
                events.publish(run.getId(), "tool_started");

                String progress = readFrame(reader);

                assertTrue(progress.contains("event:tool_started"));
                assertTrue(progress.contains("RUNNING"));
                assertNull(mapper.answer(run.getId()), "progress must be received before completion");

                trace.setStatus("SUCCEEDED");
                trace.setResultSummary("records=1");

                mapper.updateTrace(trace);
                events.publish(run.getId(), "tool_finished");
                store.finish(run, "SUCCEEDED", null, "代理后的结果", List.of(), List.of());

                String tail = readRest(reader);

                assertTrue(tail.contains("run_finished"));
                assertTrue(tail.contains("代理后的结果"));
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "teamdocs.browser-fixture", matches = "true")
    void browserFixture() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        when(model.generate(anyList(), anyList())).thenAnswer(invocation -> {
            Thread.sleep(1500);

            List<ChatMessage> messages = invocation.getArgument(0);
            ChatMessage last = messages.get(messages.size() - 1);
            AiMessage reply;

            if (last instanceof ToolExecutionResultMessage result) {
                JsonNode chunks = json.readTree(result.text()).path("chunks");

                if (chunks.isArray() && !chunks.isEmpty()) {
                    JsonNode chunk = chunks.get(0);
                    String id = chunk.path("sourceId").asText();

                    reply = AiMessage.from(json.writeValueAsString(Map.of("answer", "资料中提到：" + chunk.path("excerpt").asText() + "[" + id + "]", "citations", List.of(id))));
                } else reply = AiMessage.from("{\"answer\":\"当前空间没有检索到相关正文，请确认文档已完成解析。\",\"citations\":[]}");
            } else reply = AiMessage.from(List.of(ToolExecutionRequest.builder().id("p4-" + calls.incrementAndGet()).name("search_document_chunks").arguments("{\"keyword\":\"备份\"}").build()));

            return Response.from(reply, new TokenUsage(20, 20));
        });

        Path stop = Path.of("target/p4-browser-stop-" + System.currentTimeMillis());

        Files.writeString(Path.of("target/p4-browser-fixture.json"), json.writeValueAsString(Map.of(
                "url", "http://127.0.0.1:" + port, "token", token, "username", "p4_reader", "password", "p4-fixture-password", "stopFile", stop.toAbsolutePath().toString())));
        System.out.println("P4_BROWSER_FIXTURE_READY");

        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(15);

        while (!Files.exists(stop) && System.nanoTime() < deadline) Thread.sleep(500);
    }

    private Run running() {
        Session session = service.createSession(1L, new NewSession("SSE 验证"), USER);
        Run run = store.createRun(1L, session.getId(), 7L, new NewRun("request-" + System.nanoTime(), "测试问题")).run();

        mapper.claim(run.getId(), System.currentTimeMillis());

        return run;
    }

    private String url(Long runId) { return "http://127.0.0.1:" + port + "/spaces/1/agent/runs/" + runId + "/events"; }

    private HttpRequest request(String url, String bearer) {
        return HttpRequest.newBuilder(URI.create(url)).header("Accept", "text/event-stream").header("Authorization", "Bearer " + bearer).timeout(Duration.ofSeconds(10)).GET().build();
    }

    private InputStream open(String endpoint) throws Exception {
        HttpResponse<InputStream> response = HttpClient.newHttpClient().send(request(endpoint, token), HttpResponse.BodyHandlers.ofInputStream());

        assertEquals(200, response.statusCode());

        return response.body();
    }

    /** 在现有读超时内等待指定事件，忽略定时快照。 */
    private String readEvent(BufferedReader reader, String event) throws Exception {
        return timedRead(() -> {
            StringBuilder frame = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) frame.append(line).append('\n');
                else {
                    if (frame.toString().contains("event:" + event + "\n")) return frame.toString();

                    frame.setLength(0);
                }
            }

            throw new AssertionError("stream ended before " + event);
        });
    }

    /** 读取事件中的实际 JSON 快照。 */
    private JsonNode snapshot(String frame) throws Exception {
        String data = frame.lines().filter(line -> line.startsWith("data:")).findFirst().orElseThrow();

        return json.readTree(data.substring(5)).path("snapshot");
    }

    private String readFrame(BufferedReader reader) throws Exception {
        return timedRead(() -> {
            StringBuilder body = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null && !line.isEmpty()) body.append(line).append('\n');

            return body.toString();
        });
    }

    private String readRest(BufferedReader reader) throws Exception {
        return timedRead(() -> { StringBuilder body = new StringBuilder(); String line; while ((line = reader.readLine()) != null) body.append(line).append('\n'); return body.toString(); });
    }

    private String timedRead(Callable<String> action) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> { Thread thread = new Thread(task, "sse-test-reader"); thread.setDaemon(true); return thread; });

        try { return executor.submit(action).get(8, TimeUnit.SECONDS); }
        finally { executor.shutdownNow(); }
    }

    private void assertOrderedIds(String stream) {
        long previous = 0;

        for (String line : stream.split("\n")) if (line.startsWith("id:")) {
            long current = Long.parseLong(line.substring(3).trim());

            assertTrue(current > previous);
            previous = current;
        }
    }

    private static void waitLatch(CountDownLatch latch) {
        try { latch.await(8, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableScheduling
    @MapperScan("asia.creat.mapper")
    @ComponentScan(basePackages = "asia.creat", excludeFilters = {
            @ComponentScan.Filter(type = FilterType.REGEX, pattern = "asia[.]creat[.]teamdocsbackend[.].*"),
            @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = TeamdocsBackendApplication.class)
    })
    static class Application { }
}
