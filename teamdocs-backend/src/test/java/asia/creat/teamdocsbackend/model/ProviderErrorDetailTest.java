package asia.creat.teamdocsbackend.model;

import asia.creat.agent.model.OpenAiReasoningChatModel;
import asia.creat.config.AgentProperties;
import asia.creat.model.AiDependencyHealth;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProviderErrorDetailTest {
    @Test
    void displaysBoundedProviderFieldsWithoutCredentialsOrRawExceptionBody() throws Exception {
        String key = "test-private-credential";
        String body = new ObjectMapper().writeValueAsString(Map.of("error", Map.of(
                "code", "invalid_parameter", "param", "max_tokens",
                "message", "max_tokens must be >= 1024; key " + key + " Bearer another-secret https://private.example/path?key=x"),
                "ignored", "private unrelated field"));
        var error = invoke(body, key);
        assertEquals(400, error.httpStatus());
        assertTrue(error.diagnostic().contains("code=invalid_parameter"));
        assertTrue(error.diagnostic().contains("param=max_tokens"));
        assertTrue(error.diagnostic().contains("must be >= 1024"));
        for (String secret : List.of(key, "another-secret", "private.example", "private unrelated field")) {
            assertFalse(error.diagnostic().contains(secret));
            assertFalse(error.toString().contains(secret));
        }
        assertNull(error.getCause());
        String detail = ReflectionTestUtils.invokeMethod(AiDependencyHealth.class, "describeFailure", error);
        assertTrue(detail.contains("HTTP 400"));
        assertTrue(detail.contains("param=max_tokens"));
    }

    @Test
    void malformedOrOversizedErrorStillPreservesHttpStatus() throws Exception {
        for (String body : List.of("<html>Bad gateway</html>", "x".repeat(17000), "{}", "{\"error\":null}")) {
            var error = invoke(body, "test-key");
            assertEquals(400, error.httpStatus());
            assertNull(error.diagnostic());
        }
    }

    private OpenAiReasoningChatModel.CallFailure invoke(String body, String key) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            AgentProperties properties = new AgentProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            properties.setApiKey(key);
            properties.setModelName("test");
            properties.setStreaming(false);
            var model = new OpenAiReasoningChatModel(properties, new ObjectMapper());
            var failure = assertThrows(OpenAiReasoningChatModel.CallFailure.class,
                    () -> model.generate(List.of(UserMessage.from("test"))));
            assertEquals(1, calls.get());
            return failure;
        } finally { server.stop(0); }
    }
}
