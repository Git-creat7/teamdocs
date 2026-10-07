package asia.creat.teamdocsbackend.retrieval;

import asia.creat.agent.mcp.McpHttpClient;
import asia.creat.config.AgentProperties;
import asia.creat.config.EmbeddingProperties;
import asia.creat.config.McpProperties;
import asia.creat.config.RerankProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RetrievalActivationTest {
    /** 填写有效 Key 后自动启用，无需额外环境开关。 */
    @Test
    void validKeysActivateConfiguredFeatures() {
        new ApplicationContextRunner().withUserConfiguration(Config.class)
                .withPropertyValues("teamdocs.agent.api-key=test-agent", "teamdocs.embedding.api-key=test-embedding",
                        "teamdocs.rerank.api-key=test-rerank")
                .run(context -> {
                    assertTrue(context.getBean(AgentProperties.class).isEnabled());
                    assertTrue(context.getBean(AgentProperties.class).isAllowDocumentEgress());
                    assertTrue(context.getBean(EmbeddingProperties.class).isEnabled());
                    assertTrue(context.getBean(EmbeddingProperties.class).isAllowDocumentEgress());
                    assertTrue(context.getBean(RerankProperties.class).isEnabled());
                    assertTrue(context.getBean(RerankProperties.class).isAllowDocumentEgress());
                });
    }

    /** 空值、占位值不能开启功能，仍支持运维显式关闭属性。 */
    @Test
    void placeholdersAndExplicitDisablementStayOff() {
        new ApplicationContextRunner().withUserConfiguration(Config.class)
                .withPropertyValues("teamdocs.agent.api-key=YOUR_AGENT_KEY", "teamdocs.embedding.api-key=test-key",
                        "teamdocs.embedding.enabled=false", "teamdocs.rerank.api-key=")
                .run(context -> {
                    assertFalse(context.getBean(AgentProperties.class).isEnabled());
                    assertFalse(context.getBean(EmbeddingProperties.class).isEnabled());
                    assertFalse(context.getBean(RerankProperties.class).isEnabled());
                    assertEquals("", context.getBean(EmbeddingProperties.class).getBaseUrl());
                    assertEquals("", context.getBean(RerankProperties.class).getModelName());
                });
    }

    /** MCP 占位 Key 不连接；填入测试 Key 后直接进行发现。 */
    @Test
    void mcpKeyControlsDiscoveryWithoutExtraFlag() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();

            byte[] body = "{\"result\":{\"tools\":[]}}".getBytes(StandardCharsets.UTF_8);

            exchange.sendResponseHeaders(200, body.length);

            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();

        try {
            McpProperties properties = new McpProperties();
            McpProperties.ServerConfig config = new McpProperties.ServerConfig();
            config.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
            config.setHeaders(Map.of("Authorization", "Bearer YOUR_MCP_STEP_API_KEY"));

            properties.setServers(Map.of("test", config));

            McpHttpClient client = new McpHttpClient(properties, new ObjectMapper());
            client.init();

            assertEquals(0, calls.get());
            config.setHeaders(Map.of("Authorization", "Bearer test-key"));
            client.init();

            assertEquals(3, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Configuration
    @EnableConfigurationProperties({AgentProperties.class, EmbeddingProperties.class, RerankProperties.class})
    static class Config { }
}
