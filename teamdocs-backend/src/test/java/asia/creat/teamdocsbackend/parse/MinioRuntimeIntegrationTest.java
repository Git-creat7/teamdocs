package asia.creat.teamdocsbackend.parse;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayInputStream;
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

import static org.junit.jupiter.api.Assertions.*;

/** 验证镜像同步后的真实 mc、Compose 初始化脚本、公私桶、预览 URL 和 CORS。 */
class MinioRuntimeIntegrationTest {
    @Test
    void composeInitializationAndFileAccessWorkWithThePublishedRuntime() throws Exception {
        String image = System.getProperty("teamdocs.test.minio-image", "ghcr.io/git-creat7/teamdocs/minio@sha256:648817f3b321ec7a2f86c594ba468fa19eff8ee3ac17a07c03acf7a8a35fda33");
        String clientImage = System.getProperty("teamdocs.test.minio-client-image", "ghcr.io/git-creat7/teamdocs/mc@sha256:eb4ea9884b77704230e2423e9004d2fa738dc272876b9cc41a297d29443b8780");
        String origin = "http://localhost:5173";
        Map<?, ?> compose = new Yaml().load(Files.readString(Path.of("../docker-compose.yaml")));
        Map<?, ?> services = (Map<?, ?>) compose.get("services");
        List<?> entrypoint = (List<?>) ((Map<?, ?>) services.get("minio-init")).get("entrypoint");
        // Compose 把 $$ 转义为 $；其余脚本直接使用部署文件，不另写一份初始化逻辑。
        String script = entrypoint.get(2).toString().replace("$$", "$");

        try (Network network = Network.newNetwork();
             GenericContainer<?> server = new GenericContainer<>(image)
                     .withNetwork(network).withNetworkAliases("minio")
                     .withEnv("MINIO_ROOT_USER", "runtime-test-access")
                     .withEnv("MINIO_ROOT_PASSWORD", "runtime-test-secret")
                     .withEnv("MINIO_API_CORS_ALLOW_ORIGIN", origin)
                     .withCommand("server", "/data", "--console-address", ":9001")
                     .withExposedPorts(9000)
                     .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000))) {
            server.start();

            assertTrue(server.execInContainer("minio", "--version").getStdout().contains("RELEASE.2024-05-28T17-19-04Z"));

            for (int attempt = 0; attempt < 2; attempt++) {
                try (GenericContainer<?> init = new GenericContainer<>(clientImage)
                        .withNetwork(network)
                        .withEnv("MINIO_ROOT_USER", "runtime-test-access")
                        .withEnv("MINIO_ROOT_PASSWORD", "runtime-test-secret")
                        .withEnv("MINIO_BUCKET_PUBLIC", "runtime-public")
                        .withEnv("MINIO_BUCKET_PRIVATE", "runtime-private")
                        .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/sh", "-c"))
                        .withCommand(new String[]{"mc --version; " + script})
                        .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(90)))) {
                    init.start();

                    assertEquals(0L, init.getCurrentContainerInfo().getState().getExitCodeLong());
                    assertTrue(init.getLogs().contains("RELEASE.2025-08-13T08-35-41Z"));
                }
            }

            String endpoint = "http://" + server.getHost() + ":" + server.getMappedPort(9000);
            MinioClient client = MinioClient.builder().endpoint(endpoint)
                    .credentials("runtime-test-access", "runtime-test-secret").region("us-east-1").build();
            byte[] payload = "仅用于隔离存储验收，不包含业务文档。".getBytes(StandardCharsets.UTF_8);

            for (String bucket : List.of("runtime-public", "runtime-private")) {
                assertTrue(client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()));
                client.putObject(PutObjectArgs.builder().bucket(bucket).object("sample.txt")
                        .stream(new ByteArrayInputStream(payload), payload.length, -1)
                        .contentType("text/plain; charset=utf-8").build());
            }

            HttpClient http = HttpClient.newHttpClient();

            assertEquals(200, get(http, endpoint + "/runtime-public/sample.txt").statusCode());
            assertEquals(403, get(http, endpoint + "/runtime-private/sample.txt").statusCode());

            String signed = client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET).bucket("runtime-private").object("sample.txt").expiry(60).build());
            HttpResponse<byte[]> preview = get(http, signed);

            assertEquals(200, preview.statusCode());
            assertArrayEquals(payload, preview.body());

            HttpResponse<Void> cors = http.send(HttpRequest.newBuilder(URI.create(signed))
                    .header("Origin", origin).header("Access-Control-Request-Method", "GET")
                    .timeout(Duration.ofSeconds(10)).method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());

            assertTrue(cors.statusCode() >= 200 && cors.statusCode() < 300);
            assertEquals(origin, cors.headers().firstValue("access-control-allow-origin").orElse(""));
        }
    }

    private HttpResponse<byte[]> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }
}
