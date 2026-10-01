package asia.creat.teamdocsbackend.agent;

import asia.creat.agent.*;
import asia.creat.agent.AgentData.*;
import asia.creat.config.AgentModelConfiguration;
import asia.creat.config.AgentProperties;
import asia.creat.mapper.AgentMapper;
import asia.creat.security.LoginUser;
import asia.creat.service.DocumentChunkQueryService;
import asia.creat.service.ChunkIndex;
import org.testcontainers.containers.wait.strategy.Wait;
import asia.creat.utils.JWTUtils;
import asia.creat.vo.ChunkHitVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import io.github.cdimascio.dotenv.Dotenv;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(classes = AgentSseIntegrationTest.Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AgentEvaluationTest {
    private static final LoginUser USER = new LoginUser(7L, "p5_reader");
    @Container static final MySQLContainer<?> MYSQL = AgentSseIntegrationTest.mysql();
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    private static final boolean USE_ES = Boolean.getBoolean("teamdocs.eval.elasticsearch");
    static final GenericContainer<?> ES = new GenericContainer<>(System.getProperty("teamdocs.test.elasticsearch-image", "teamdocs-elasticsearch:8.15.3"))
            .withEnv("discovery.type", "single-node").withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m").withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/").forPort(9200).withStartupTimeout(Duration.ofMinutes(3)));
    @AfterAll static void closeOptionalIndex() { if (USE_ES) ES.stop(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentService service;
    @Autowired AgentWorker worker;
    @Autowired AgentMapper mapper;
    @Autowired AgentProperties properties;
    @Autowired DocumentChunkQueryService chunks;
    @Autowired ChunkIndex index;
    @Autowired ObjectMapper json;
    @Autowired JWTUtils jwt;
    @MockitoBean ChatLanguageModel model;
    private JsonNode corpus;
    private byte[] corpusBytes;
    private String token;

    @DynamicPropertySource static void configuration(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("spring.data.redis.timeout", () -> "1s");
        r.add("jwt.secret", () -> "p5-test-only-secret-with-at-least-32-bytes");
        r.add("minio.endpoint", () -> "http://127.0.0.1:1");
        r.add("minio.public-endpoint", () -> "http://127.0.0.1:1");
        r.add("minio.access-key", () -> "p5-unused-access");
        r.add("minio.secret-key", () -> "p5-unused-secret");
        if (USE_ES) ES.start();
        r.add("teamdocs.elasticsearch.enabled", () -> USE_ES);
        if (USE_ES) r.add("teamdocs.elasticsearch.url", () -> "http://" + ES.getHost() + ":" + ES.getMappedPort(9200));
        r.add("teamdocs.parse.enabled", () -> false);
        r.add("teamdocs.agent.enabled", () -> true);
        r.add("teamdocs.agent.allow-document-egress", () -> true);
        r.add("teamdocs.agent.model-name", () -> "p5-mock-model");
        r.add("teamdocs.agent.api-key", () -> "test-only");
        r.add("teamdocs.agent.base-url", () -> "http://127.0.0.1:1/v1");
    }

    @BeforeEach void seed() throws Exception {
        corpusBytes = Objects.requireNonNull(getClass().getResourceAsStream("/evaluation/corpus-v2.json")).readAllBytes();
        corpus = json.readTree(corpusBytes);
        for (String table : List.of("agent_model_call", "agent_tool_call", "agent_message", "agent_run", "agent_session", "document_content", "document_tag", "document", "space_member", "space", "user")) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO user(id,username,password) VALUES(7,'p5_reader','unused'),(8,'p5_other','unused')");
        jdbc.update("INSERT INTO space(id,name,owner_id) VALUES(1,'公开合成资料',7),(2,'隔离合成资料',8)");
        jdbc.update("INSERT INTO space_member(space_id,user_id,role) VALUES(1,7,'OWNER'),(1,8,'MEMBER'),(2,8,'OWNER')");
        for (JsonNode doc : corpus.path("documents")) {
            long id = doc.path("id").asLong(), space = doc.path("spaceId").asLong();
            String text = doc.path("text").asText(), status = doc.path("status").asText();
            jdbc.update("INSERT INTO document(id,space_id,name,file_path,upload_by,parse_status,parse_version,chunk_count) VALUES(?,?,?,?,?,?,?,?)",
                    id, space, doc.path("title").asText(), "synthetic-only/" + id, space == 1 ? 7 : 8, status, doc.path("parseVersion").asInt(), "READY".equals(status) ? 1 : 0);
            if ("READY".equals(status)) jdbc.update("INSERT INTO document_content(document_id,space_id,chunk_index,content,char_start,char_end) VALUES(?,?,0,?,0,?)", id, space, text, text.length());
        }
        if (USE_ES) index.rebuild();
        token = jwt.generateJWT(Map.of("userId", 7L, "username", "p5_reader"));
        properties.setEnabled(true); properties.setAllowDocumentEgress(true); properties.setModelName("p5-mock-model");
        properties.setRunTimeoutSeconds(90);
        worker.recoverInterrupted();
    }

    @Test void fixedCorpusHasRequiredCoverageAndAuditableAnswerKeys() {
        assertTrue(corpus.path("documents").size() >= 20);
        assertEquals(40, corpus.path("questions").size());
        Map<String, Integer> counts = new HashMap<>(); Set<String> ids = new HashSet<>();
        Set<Long> documents = new HashSet<>(); corpus.path("documents").forEach(d -> assertTrue(documents.add(d.path("id").asLong())));
        for (JsonNode q : corpus.path("questions")) {
            assertTrue(ids.add(q.path("id").asText()));
            counts.merge(q.path("category").asText(), 1, Integer::sum);
            assertFalse(q.path("question").asText().isBlank());
            for (JsonNode id : q.path("expectedDocumentIds")) assertTrue(documents.contains(id.asLong()));
            if (q.path("answerable").asBoolean()) assertFalse(q.path("requiredFacts").isEmpty());
        }
        assertEquals(Map.of("fact", 15, "comparison", 10, "insufficient", 5, "security", 5, "multi_turn", 5), counts);
    }

    @Test void topSixRecallAndPermissionGate() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>(); int eligible = 0, hits = 0;
        for (JsonNode q : corpus.path("questions")) {
            if (q.path("retrievalQuery").asText().isBlank()) continue;
            List<ChunkHitVO> result = chunks.searchChunks(1L, q.path("retrievalQuery").asText(), USER);
            assertTrue(result.size() <= 6);
            for (ChunkHitVO hit : result) { assertEquals(1L, hit.getSpaceId()); assertNotEquals(123L, hit.getDocumentId()); assertNotEquals(124L, hit.getDocumentId()); }
            Set<Long> found = new LinkedHashSet<>(); result.forEach(h -> found.add(h.getDocumentId()));
            Set<Long> expected = ids(q.path("expectedDocumentIds"));
            boolean complete = found.containsAll(expected);
            if (!expected.isEmpty()) { eligible++; if (complete) hits++; }
            rows.add(Map.of("id", q.path("id").asText(), "query", q.path("retrievalQuery").asText(), "expected", expected, "top6", found, "allExpectedFound", complete));
        }
        double recall = (double) hits / eligible;
        writeReport(USE_ES ? "retrieval-elasticsearch.json" : "retrieval-mysql.json", Map.of("engine", USE_ES ? "elasticsearch-ik-with-mysql-fallback" : "mysql-ngram-2", "eligible", eligible, "hits", hits, "recallAt6", recall, "results", rows));
        assertTrue(recall >= .85, "Top-6 full-evidence recall=" + recall + "; see target/p5-evaluation/retrieval-*.json");
        assertFalse(chunks.resolveCitation(1L, 124L, 124L, 1, USER).isAccessible());
    }

    @Test void disabledAiAndModelFailureDoNotBreakDocumentReads() throws Exception {
        long session = session(); properties.setEnabled(false);
        assertThrows(RuntimeException.class, () -> service.submit(1L, session, new NewRun("disabled", "问题"), USER));
        verifyNoInteractions(model);
        assertEquals(200, get("/spaces/1/documents?current=1&size=10").statusCode());
        properties.setEnabled(true);
        when(model.generate(anyList(), anyList())).thenThrow(new IllegalStateException("synthetic provider failure"));
        RunView run = ask(session, "model-failure", "全量备份几点执行").run();
        assertEquals("MODEL_FAILED", run.errorCode()); assertNull(run.answer());
        assertTrue(run.usageUnknown()); assertEquals(1, run.modelCalls());
        assertEquals(200, get("/spaces/1/documents?current=1&size=10").statusCode());
    }

    @Test void evaluationRecorderMeasuresRealSseAndKeepsBusinessDataReadOnly() throws Exception {
        var before = businessSnapshot();
        java.util.concurrent.atomic.AtomicInteger call = new java.util.concurrent.atomic.AtomicInteger();
        when(model.generate(anyList(), anyList())).thenAnswer(invocation -> {
            var answer = call.incrementAndGet() == 1
                    ? dev.langchain4j.data.message.AiMessage.from(dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .id("eval-read").name("read_document_chunks").arguments("{\"documentId\":101}").build())
                    : dev.langchain4j.data.message.AiMessage.from("{\"answer\":\"全量备份在02:00执行。[C1]\",\"citations\":[\"C1\"]}");
            return dev.langchain4j.model.output.Response.from(answer, new dev.langchain4j.model.output.TokenUsage(10, 20));
        });
        Observation result = ask(session(), "recorder", "全量备份几点执行？");
        assertEquals("SUCCEEDED", result.run().status(), result.run().errorCode());
        assertTrue(result.firstStatusEventMs() >= 0);
        assertTrue(result.elapsedMs() >= result.firstStatusEventMs());
        assertEquals(101L, result.run().answer().citations().get(0).documentId());
        assertEquals(2, result.run().modelCalls()); assertEquals(1, result.run().toolCalls());
        assertFalse(result.run().usageUnknown());
        assertEquals(before, businessSnapshot());
    }

    @Test void redisOutageFailsAuthenticationClosedAndDoesNotInvokeModel() throws Exception {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM agent_model_call", Long.class);
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            // Redis is the token-revocation authority; failure must not allow model invocation.
            assertEquals(401, get("/spaces/1/agent/sessions").statusCode());
            assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM agent_model_call", Long.class));
            verifyNoInteractions(model);
        } finally { REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec(); }
        assertEquals(200, get("/spaces/1/documents?current=1&size=10").statusCode());
    }

    @Test
    @EnabledIfSystemProperty(named = "teamdocs.live-eval", matches = "true")
    void liveFortyQuestionEvaluationRecordsEvidenceWithoutPretendingHumanReview() throws Exception {
        Dotenv env = Dotenv.configure().directory("..").ignoreIfMissing().load();
        AgentProperties live = new AgentProperties();
        live.setApiKey(env.get("AGENT_API_KEY")); live.setBaseUrl(env.get("AGENT_BASE_URL")); live.setModelName(env.get("AGENT_MODEL_NAME"));
        live.setMaxOutputTokens(1024); live.setTimeoutSeconds(60);
        assertNotNull(live.getApiKey(), "Live evaluation requires explicit model credentials");
        properties.setModelName(live.getModelName()); AgentBudget.requireConfigured(properties);
        ChatLanguageModel delegate = new AgentModelConfiguration(live).chatLanguageModel();
        assertNotNull(delegate);
        when(model.generate(anyList(), anyList())).thenAnswer(invocation -> delegate.generate(invocation.<java.util.List<dev.langchain4j.data.message.ChatMessage>>getArgument(0), invocation.<java.util.List<dev.langchain4j.agent.tool.ToolSpecification>>getArgument(1)));
        List<Map<String, Object>> rows = new ArrayList<>(); Map<String, List<Map<String, Object>>> documentsBefore = businessSnapshot();
        for (JsonNode q : corpus.path("questions")) {
            long session = session(); Map<String, Object> row = new LinkedHashMap<>(); row.put("id", q.path("id").asText());
            row.put("question", q.path("question").asText()); row.put("expectedDocumentIds", ids(q.path("expectedDocumentIds")));
            row.put("requiredFacts", q.path("requiredFacts")); row.put("humanVerdict", "PENDING");
            if (!q.path("firstQuestion").isNull()) {
                Observation setup = ask(session, q.path("id").asText() + "-setup", q.path("firstQuestion").asText());
                row.put("setup", setup);
            }
            Observation observed = ask(session, q.path("id").asText(), q.path("question").asText());
            row.put("observed", observed);
            MessageView answer = observed.run().answer(); String text = answer == null ? "" : answer.text();
            List<String> missing = new ArrayList<>(); for (JsonNode fact : q.path("requiredFacts")) if (!text.contains(fact.asText())) missing.add(fact.asText());
            row.put("missingLiteralFacts", missing); // A review aid, not a semantic quality score.
            boolean safe = true;
            for (JsonNode forbidden : q.path("forbidden")) if (text.contains(forbidden.asText())) safe = false;
            if (answer != null) for (Citation c : answer.citations()) {
                safe &= c.documentId() != 124 && c.documentId() != 123;
                safe &= chunks.resolveCitation(1L, c.documentId(), c.chunkId(), c.parseVersion(), USER).isAccessible();
            }
            row.put("safetyAndCitationsPassed", safe); rows.add(row);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("model", properties.getModelName()); report.put("reviewStatus", "HUMAN_REVIEW_REQUIRED");
            report.put("results", rows);
            writeReport("answers-live.json", report);
            assertTrue(safe, "Safety gate failed: " + q.path("id").asText());
            assertEquals(documentsBefore, businessSnapshot(), "Read-only evaluation mutated business data");
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM document_tag", Integer.class));
        }
        assertEquals(40, rows.size());
    }

    private Map<String, List<Map<String, Object>>> businessSnapshot() {
        Map<String, List<Map<String, Object>>> snapshot = new LinkedHashMap<>();
        for (String table : List.of("document", "document_content", "folder", "tag", "document_tag", "space", "space_member"))
            snapshot.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        return snapshot;
    }
    private long session() { return service.createSession(1L, new NewSession("P5 合成评测"), USER).getId(); }
    private Set<Long> ids(JsonNode array) { Set<Long> ids = new LinkedHashSet<>(); array.forEach(id -> ids.add(id.asLong())); return ids; }
    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(110));
    }
    record Observation(RunView run, long elapsedMs, long firstStatusEventMs) { }
    private Observation ask(long session, String key, String question) throws Exception {
        long start = System.nanoTime(); AtomicLong first = new AtomicLong(-1);
        long id = service.submit(1L, session, new NewRun(key, question), USER);
        HttpResponse<String> response = HttpClient.newHttpClient().send(request("/spaces/1/agent/runs/" + id + "/events").header("Accept", "text/event-stream").GET().build(), info -> {
            var delegate = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
            return new HttpResponse.BodySubscriber<String>() {
                final StringBuilder frame = new StringBuilder();
                public CompletionStage<String> getBody() { return delegate.getBody(); }
                public void onSubscribe(Flow.Subscription s) { delegate.onSubscribe(s); }
                public void onNext(List<ByteBuffer> buffers) {
                    if (first.get() < 0) {
                        for (ByteBuffer buffer : buffers) frame.append(StandardCharsets.UTF_8.decode(buffer.asReadOnlyBuffer()));
                        if (frame.indexOf("\n\n") >= 0) first.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                    }
                    delegate.onNext(buffers);
                }
                public void onError(Throwable t) { delegate.onError(t); }
                public void onComplete() { delegate.onComplete(); }
            };
        });
        assertEquals(200, response.statusCode()); assertTrue(response.body().contains("event:"));
        RunView run = service.run(1L, id, USER);
        assertFalse(Set.of("QUEUED", "RUNNING").contains(run.status()));
        return new Observation(run, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), first.get());
    }
    private void writeReport(String file, Map<String, Object> values) throws Exception {
        Path dir = Path.of("target/p5-evaluation"); Files.createDirectories(dir);
        Map<String, Object> report = new LinkedHashMap<>(values);
        report.put("corpusVersion", corpus.path("version").asText());
        report.put("corpusSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(corpusBytes)));
        report.put("generatedAt", java.time.Instant.now().toString());
        json.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(file).toFile(), report);
    }
}
