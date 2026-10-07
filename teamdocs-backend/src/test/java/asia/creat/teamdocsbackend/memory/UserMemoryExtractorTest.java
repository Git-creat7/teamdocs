package asia.creat.teamdocsbackend.memory;

import asia.creat.agent.AgentData;
import asia.creat.agent.AgentRepository;
import asia.creat.agent.model.OpenAiReasoningChatModel.RequestControl;
import asia.creat.agent.model.OpenAiReasoningChatModel;
import asia.creat.config.AgentProperties;
import asia.creat.memory.UserMemoryExtractor;
import asia.creat.model.UserModelData;
import asia.creat.model.UserModelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import static org.junit.jupiter.api.Assertions.*;

class UserMemoryExtractorTest {
    @Test
    void memoryUsesTheSourceRunsPersonalModelSnapshot() throws Exception {
        AgentProperties properties = new AgentProperties();
        var models = Mockito.mock(UserModelService.class);
        var runs = Mockito.mock(AgentRepository.class);
        var selected = Mockito.mock(OpenAiReasoningChatModel.class);
        var run = AgentData.Run.builder().id(4L).userId(1L).modelConfigCiphertext("encrypted-snapshot").build();
        var credentials = new UserModelData.Credentials("https://api.example.com/v1", "mine", "private-key");
        var extractor = new UserMemoryExtractor(properties, models, runs);
        Mockito.when(runs.run(4L)).thenReturn(run);
        Mockito.when(models.credentials(run)).thenReturn(credentials);
        Mockito.when(models.model(credentials, true)).thenReturn(selected);
        Mockito.when(selected.generate(ArgumentMatchers.anyList(), ArgumentMatchers.anyList(),
                ArgumentMatchers.isNull(), ArgumentMatchers.any())).thenReturn(
                Response.from(AiMessage.from("{\"candidates\":[]}")));

        assertTrue(extractor.canProcessJobs());
        assertTrue(extractor.extractForRun(4L, "我主要用 Java", List.of(), new RequestControl()).isEmpty());
        Mockito.verify(models).credentials(run);
        Mockito.verify(models).model(credentials, true);
    }

    @Test
    void usesBoundedIndependentNonStreamingRequestsWithNoToolsOrAssistantMessages() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AtomicReference<JsonNode> payload = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            payload.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            String result = "{\"candidates\":[{\"key\":\"code_language\",\"value\":\"Java\",\"evidence\":\"我主要用 Java\"}]}";
            byte[] body = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                    "message", Map.of("role", "assistant", "content", result)))));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            AgentProperties config = new AgentProperties();
            config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            config.setApiKey("local-test-key");
            config.setModelName("test-model");
            config.setStreaming(true);
            config.setMaxOutputTokens(2048);
            config.setTimeoutSeconds(60);
            UserMemoryExtractor extractor = new UserMemoryExtractor(config, null, null);
            assertTrue(extractor.available());
            var candidates = extractor.extract("我主要用 Java", List.of(), new RequestControl());
            assertEquals("Java", candidates.get(0).value());
            assertEquals(512, payload.get().path("max_tokens").asInt());
            assertFalse(payload.get().path("stream").asBoolean());
            assertFalse(payload.get().has("tools"));
            assertEquals(2, payload.get().path("messages").size());
            assertEquals("system", payload.get().path("messages").get(0).path("role").asText());
            assertEquals("user", payload.get().path("messages").get(1).path("role").asText());
            assertTrue(config.isStreaming());
            assertEquals(2048, config.getMaxOutputTokens());
            assertEquals(60, config.getTimeoutSeconds());

            payload.set(null);
            assertTrue(extractor.extract("我的密码是 secret", List.of(), new RequestControl()).isEmpty());
            assertNull(payload.get());
            config.setAllowDocumentEgress(false);
            assertFalse(extractor.available());
        } finally { server.stop(0); }
    }
}
