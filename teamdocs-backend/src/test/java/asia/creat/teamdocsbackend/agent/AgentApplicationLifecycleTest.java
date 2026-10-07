package asia.creat.teamdocsbackend.agent;

import com.fasterxml.jackson.databind.*;
import java.io.File;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

/** Starts real, separate production JVMs against the same disposable database; never reads the project's .env. */
@Testcontainers
class AgentApplicationLifecycleTest {
    @Container static final MySQLContainer<?> MYSQL = AgentSseIntegrationTest.mysql();
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    final ObjectMapper json = new ObjectMapper();
    JdbcTemplate jdbc;
    Path work;

    @BeforeEach void seed() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));

        for (String table : List.of("agent_model_call", "agent_tool_call", "agent_message", "agent_run", "agent_session", "document", "folder", "space_member", "space", "user")) jdbc.update("DELETE FROM " + table);

        jdbc.update("INSERT INTO user(id,username,password) VALUES(7,'p5_lifecycle',?)", new BCryptPasswordEncoder().encode("p5-lifecycle-password"));
        jdbc.update("INSERT INTO space(id,name,owner_id) VALUES(1,'lifecycle-fixture',7)");
        jdbc.update("INSERT INTO space_member(space_id,user_id,role) VALUES(1,7,'OWNER')");
        jdbc.update("INSERT INTO document(id,space_id,name,file_path,upload_by,parse_status) VALUES(1,1,'KEEP-DOCUMENT','synthetic-only',7,'PENDING')");
        jdbc.update("INSERT INTO agent_session(id,space_id,user_id,title) VALUES(1,1,7,'KEEP-SESSION')");
        work = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "p5-lifecycle-");
    }

    @Test void processRestartWithAiDisabledPreservesDocumentsAndUnknownUsage() throws Exception { exercise(null); }

    @Test
    @EnabledIfSystemProperty(named = "teamdocs.rollback.jar", matches = ".+")
    void previousPackagedApplicationReadsCurrentSchemaWithoutReinitializingData() throws Exception {
        Path old = Path.of(System.getProperty("teamdocs.rollback.jar")).toAbsolutePath();

        assertTrue(Files.isRegularFile(old), "Supply an explicitly built previous-version JAR");
        exercise(old);
    }

    private void exercise(Path rollbackJar) throws Exception {
        pending(1);

        try (App current = start(null, "current")) {
            check(current, 1);

            String token = login(current);
            JsonNode created = request(current, "/spaces/1/folders", "POST", "{\"name\":\"KEEP-FOLDER\",\"parentId\":0}", token);

            assertEquals(1, created.path("code").asInt());
            pending(2);
            // Abrupt termination exercises restart recovery, not a direct call to recoverInterrupted().
            current.process.destroyForcibly();

            assertTrue(current.process.waitFor(15, TimeUnit.SECONDS));
        }

        try (App next = start(rollbackJar, rollbackJar == null ? "restarted" : "rollback")) {
            check(next, 2);

            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM folder WHERE name='KEEP-FOLDER'", Integer.class));

            String token = login(next);

            assertTrue(request(next, "/spaces/1/folders", "GET", null, token).toString().contains("KEEP-FOLDER"));
            assertTrue(request(next, "/spaces/1/agent/sessions", "GET", null, token).toString().contains("KEEP-SESSION"));

            JsonNode disabled = request(next, "/spaces/1/agent/sessions/1/runs", "POST", "{\"clientRequestId\":\"disabled\",\"question\":\"do not call model\"}", token);

            assertEquals(0, disabled.path("code").asInt());
            assertEquals("AI 执行服务尚未就绪或模型未配置", disabled.path("msg").asText());
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM agent_model_call", Integer.class));
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("mode", rollbackJar == null ? "same-artifact-process-restart" : "previous-jar-rollback");

        if (rollbackJar != null) {
            report.put("previousJarSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(rollbackJar))));
            report.put("previousRevision", System.getProperty("teamdocs.rollback.revision", "unspecified"));
        }

        report.put("documentsPreserved", true);
        report.put("foldersPreserved", true);
        report.put("sessionsPreserved", true);
        report.put("unknownUsagePreserved", true);
        report.put("modelRequestsReplayed", 0);
        report.put("databaseReinitialized", false);

        Path output = Path.of("target/p5-evaluation");

        Files.createDirectories(output);
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve(rollbackJar == null ? "process-restart.json" : "application-rollback.json").toFile(), report);
    }

    private void pending(int id) {
        jdbc.update("INSERT INTO agent_run(id,session_id,space_id,user_id,client_request_id,request_hash,model_name,status,model_calls,max_model_calls,max_tool_calls,max_input_tokens,max_output_tokens,deadline_ms) VALUES(?,1,1,7,?,?,'fixture','RUNNING',1,6,8,16000,1024,?)",
                id, "pending-" + id, "0".repeat(64), System.currentTimeMillis() + 90000);
        jdbc.update("INSERT INTO agent_model_call(run_id,user_id,sequence,estimated_input,max_output) VALUES(?,7,1,100,100)", id);
    }

    private void check(App app, int count) throws Exception {
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM document WHERE name='KEEP-DOCUMENT'", Integer.class));
        assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM agent_run WHERE status='FAILED' AND error_code='PROCESS_INTERRUPTED'", Integer.class));
        assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM agent_model_call WHERE usage_known=0", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM agent_message", Integer.class));
        assertTrue(request(app, "/spaces/1/documents?current=1&size=10", "GET", null, login(app)).toString().contains("KEEP-DOCUMENT"));
    }

    private String login(App app) throws Exception {
        JsonNode value = request(app, "/user/login", "POST", "{\"username\":\"p5_lifecycle\",\"password\":\"p5-lifecycle-password\"}", null);

        assertEquals(1, value.path("code").asInt(), value.path("msg").asText());

        String token = value.path("data").path("token").asText();

        assertFalse(token.isBlank());

        return token;
    }

    private JsonNode request(App app, String path, String method, String body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port + path)).timeout(Duration.ofSeconds(10));

        if (token != null) builder.header("Authorization", "Bearer " + token);

        if (body == null) builder.GET(); else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));

        HttpResponse<String> response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());

        return json.readTree(response.body());
    }

    private App start(Path jar, String label) throws Exception {
        int port;

        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }

        List<String> args = new ArrayList<>(List.of("-Xmx320m", "-Dspring.devtools.restart.enabled=false", "-Dspring.devtools.livereload.enabled=false"));

        if (jar == null) {
            // Exclude test classes: production component scanning must not discover test configurations.
            String cp = Arrays.stream(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")).split(Pattern.quote(File.pathSeparator)))
                    .map(p -> Path.of(p).toAbsolutePath().normalize()).filter(p -> !p.endsWith("test-classes")).map(Path::toString).collect(Collectors.joining(File.pathSeparator));

            args.addAll(List.of("-cp", cp, "asia.creat.TeamdocsBackendApplication"));
        } else args.addAll(List.of("-jar", jar.toString()));

        args.addAll(List.of("--server.address=127.0.0.1", "--server.port=" + port,
                "--spring.datasource.url=" + MYSQL.getJdbcUrl(), "--spring.datasource.username=" + MYSQL.getUsername(), "--spring.datasource.password=" + MYSQL.getPassword(),
                "--spring.data.redis.host=" + REDIS.getHost(), "--spring.data.redis.port=" + REDIS.getMappedPort(6379), "--spring.data.redis.password=",
                "--jwt.secret=p5-process-test-only-key-with-at-least-32-bytes", "--teamdocs.agent.enabled=false", "--teamdocs.agent.api-key=", "--teamdocs.agent.model-name=", "--teamdocs.agent.allow-document-egress=false",
                "--teamdocs.parse.enabled=false", "--teamdocs.elasticsearch.enabled=false", "--minio.endpoint=http://127.0.0.1:1", "--minio.public-endpoint=http://127.0.0.1:1",
                "--minio.access-key=unused", "--minio.secret-key=unused", "--minio.bucket-public=test-public", "--minio.bucket-private=test-private"));

        Path argFile = work.resolve(label + ".args");

        Files.write(argFile, args.stream().map(s -> "\"" + s.replace("\\", "/").replace("\"", "\\\"") + "\"").toList());

        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + argFile);

        builder.directory(work.toFile()).redirectErrorStream(true).redirectOutput(work.resolve(label + ".log").toFile());
        // Never inherit model keys or production dependency addresses into the child process.
        builder.environment().keySet().removeIf(key -> key.startsWith("AGENT_") || key.startsWith("DB_") || key.startsWith("REDIS_") || key.startsWith("MINIO_") || key.startsWith("SPRING_") || key.equals("JAVA_TOOL_OPTIONS") || key.equals("JDK_JAVA_OPTIONS"));

        App app = new App(builder.start(), port);

        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(75);

            while (app.process.isAlive() && System.nanoTime() < deadline) {
                try { if ("UP".equals(request(app, "/actuator/health", "GET", null, null).path("status").asText())) return app; }
                catch (Exception ignored) { }

                Thread.sleep(250);
            }

            fail("Application did not become ready; see " + work.resolve(label + ".log"));

            return app;
        } catch (Throwable failure) { app.close(); throw failure; }
    }

    private static class App implements AutoCloseable {
        final Process process;
        final int port;

        App(Process process, int port) { this.process = process; this.port = port; }

        public void close() throws Exception { process.destroy(); if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(15, TimeUnit.SECONDS); } }
    }
}
