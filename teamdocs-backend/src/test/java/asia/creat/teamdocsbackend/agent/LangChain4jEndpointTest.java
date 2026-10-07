package asia.creat.teamdocsbackend.agent;

import asia.creat.config.AgentModelConfiguration;
import asia.creat.config.AgentProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LangChain4jEndpointTest {
    @ParameterizedTest
    @CsvSource({"'',/v1/chat/completions", "/,/v1/chat/completions",
            "/v1,/v1/chat/completions", "/gateway/api,/gateway/api/chat/completions"})
    void applicationUsesConfiguredEndpoint(String basePath, String expectedPath) throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());

            byte[] response = """
                    {"id":"test","choices":[{"index":0,"message":{"role":"assistant","content":"收到"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                    """.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            AgentProperties properties = new AgentProperties();
            properties.setApiKey("local-test-key");
            properties.setModelName("endpoint-test");
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + basePath);

            var model = new AgentModelConfiguration(properties).chatLanguageModel();

            assertEquals("收到", model.generate("sample"));
            assertEquals(expectedPath, requestPath.get());
        } finally {
            server.stop(0);
        }
    }
}
