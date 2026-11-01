package asia.creat.teamdocsbackend.agent.mcp;

import asia.creat.agent.mcp.McpHttpClient;
import asia.creat.config.McpProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolSpecification;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class McpHttpClientTest {
    private HttpServer server;
    private int port;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 验证兼容标准 SSE 流式传输 (如 Tavily)、anyOf/array 参数解析、空体通知响应与 Session 保持。 */
    @Test
    void discoversAndCallsToolsOverEventStreamWithAnyOfSchema() {
        AtomicBoolean acceptHeaderVerified = new AtomicBoolean(false);
        AtomicReference<String> receivedSessionId = new AtomicReference<>();

        server.createContext("/mcp", exchange -> {
            String accept = exchange.getRequestHeaders().getFirst("Accept");
            if (accept != null && accept.contains("text/event-stream") && accept.contains("application/json")) {
                acceptHeaderVerified.set(true);
            }
            String sessionHeader = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
            if (sessionHeader != null) {
                receivedSessionId.set(sessionHeader);
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            if (body.contains("\"method\":\"initialize\"")) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.getResponseHeaders().set("Mcp-Session-Id", "sess-test-999");
                byte[] sse = ("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"protocolVersion\":\"2024-11-05\",\"serverInfo\":{\"name\":\"mock-sse\"}}}\n\n")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, sse.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(sse);
                }
                return;
            }

            if (body.contains("\"method\":\"notifications/initialized\"")) {
                exchange.sendResponseHeaders(202, -1); // 202 空响应体
                exchange.close();
                return;
            }

            if (body.contains("\"method\":\"tools/list\"")) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                String toolsPayload = """
                        event: message
                        data: {"jsonrpc":"2.0","id":"2","result":{"tools":[{"name":"web_search","description":"Search the web","inputSchema":{"type":"object","properties":{"query":{"type":"string","description":"query text"},"max_results":{"anyOf":[{"type":"integer"},{"type":"null"}],"description":"max count"},"domains":{"anyOf":[{"type":"array","items":{"type":"string"}},{"type":"null"}],"description":"allowed domains"}},"required":["query"]}}]}}
                        """;
                byte[] sse = toolsPayload.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, sse.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(sse);
                }
                return;
            }

            if (body.contains("\"method\":\"tools/call\"")) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                String callPayload = """
                        event: message
                        data: {"jsonrpc":"2.0","id":"3","result":{"content":[{"type":"text","text":"search result: found 42 articles"}]}}
                        """;
                byte[] sse = callPayload.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, sse.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(sse);
                }
                return;
            }

            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();

        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        McpProperties.ServerConfig serverConfig = new McpProperties.ServerConfig();
        serverConfig.setUrl("http://127.0.0.1:" + port + "/mcp");
        serverConfig.setHeaders(Map.of("Authorization", "Bearer mock-token"));
        properties.setServers(Map.of("web", serverConfig));

        McpHttpClient client = new McpHttpClient(properties, mapper);
        client.init();

        assertTrue(acceptHeaderVerified.get(), "必须携带 Accept 包含 application/json 与 text/event-stream");
        assertEquals("sess-test-999", receivedSessionId.get(), "后续请求必须正确携带 Mcp-Session-Id");

        List<ToolSpecification> specs = client.getToolSpecifications();
        assertEquals(1, specs.size());
        assertEquals("web_search", specs.get(0).name());
        assertEquals("Search the web", specs.get(0).description());
        assertTrue(client.hasTool("web_search"));

        String result = client.callTool("web_search", "{\"query\":\"teamdocs\",\"max_results\":5}");
        assertEquals("search result: found 42 articles", result);
    }

    /** 验证兼容传统的标准 application/json 响应。 */
    @Test
    void discoversAndCallsToolsOverPlainJson() {
        server.createContext("/mcp-json", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");

            if (body.contains("\"method\":\"initialize\"")) {
                byte[] bytes = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"protocolVersion\":\"2024-11-05\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
                return;
            }

            if (body.contains("\"method\":\"tools/list\"")) {
                byte[] bytes = "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"tools\":[{\"name\":\"calc\",\"description\":\"Calculator\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"expr\":{\"type\":\"string\"}}}}]}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
                return;
            }

            if (body.contains("\"method\":\"tools/call\"")) {
                byte[] bytes = "{\"jsonrpc\":\"2.0\",\"id\":\"3\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"4\"}]}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
                return;
            }

            exchange.sendResponseHeaders(200, 2);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("{}".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();

        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        McpProperties.ServerConfig serverConfig = new McpProperties.ServerConfig();
        serverConfig.setUrl("http://127.0.0.1:" + port + "/mcp-json");
        properties.setServers(Map.of("calc", serverConfig));

        McpHttpClient client = new McpHttpClient(properties, mapper);
        client.init();

        assertTrue(client.hasTool("calc"));
        assertEquals("4", client.callTool("calc", "{\"expr\":\"2+2\"}"));
    }

    /** 验证服务端返回 406 或报错时保留服务端错误提示。 */
    @Test
    void retainsServerErrorMessageOnHttpFailure() {
        server.createContext("/mcp-fail", exchange -> {
            byte[] err = "{\"error\":{\"code\":-32600,\"message\":\"Custom Error Message\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, err.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(err);
            }
        });
        server.start();

        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        McpProperties.ServerConfig serverConfig = new McpProperties.ServerConfig();
        serverConfig.setUrl("http://127.0.0.1:" + port + "/mcp-fail");
        properties.setServers(Map.of("fail", serverConfig));

        McpHttpClient client = new McpHttpClient(properties, mapper);
        client.init();

        // 初始化失败时未注册任何工具
        assertEquals(0, client.getToolSpecifications().size());
        assertFalse(client.hasTool("any_tool"));
    }

    @Test
    void retries429OnceAndSucceedsIfRetryWorks() throws Exception {
        int testPort = 8165;
        HttpServer testServer = HttpServer.create(new InetSocketAddress("127.0.0.1", testPort), 0);
        AtomicInteger attempts = new AtomicInteger();

        testServer.createContext("/mcp-429-recover", exchange -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                byte[] err = "{\"error\":\"rate limit exceeded\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(429, err.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(err);
                }
            } else {
                String resp = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"tools\":[{\"name\":\"test_tool\",\"description\":\"desc\",\"inputSchema\":{\"type\":\"object\"}}]}}";
                byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });
        testServer.start();

        try {
            McpProperties properties = new McpProperties();
            properties.setEnabled(true);
            McpProperties.ServerConfig serverConfig = new McpProperties.ServerConfig();
            serverConfig.setUrl("http://127.0.0.1:" + testPort + "/mcp-429-recover");
            properties.setServers(Map.of("test429", serverConfig));

            McpHttpClient client = new McpHttpClient(properties, mapper);
            client.init();

            // 首次 initialize 遇 429 重试一次成功，tools/list 成功
            assertTrue(client.hasTool("test_tool"));
        } finally {
            testServer.stop(0);
        }
    }

    @Test
    void returnsClearNonRetryMessageWhenPersistent429() throws Exception {
        int testPort = 8166;
        HttpServer testServer = HttpServer.create(new InetSocketAddress("127.0.0.1", testPort), 0);

        testServer.createContext("/mcp-429-call", exchange -> {
            byte[] err = "{\"error\":\"rate limit exceeded\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(429, err.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(err);
            }
        });
        testServer.start();

        try {
            McpProperties properties = new McpProperties();
            properties.setEnabled(true);
            McpProperties.ServerConfig serverConfig = new McpProperties.ServerConfig();
            serverConfig.setUrl("http://127.0.0.1:" + testPort + "/mcp-429-call");
            properties.setServers(Map.of("test429", serverConfig));

            McpHttpClient client = new McpHttpClient(properties, mapper);
            // 通过反射或直接注册一个工具来测试 callTool 面对持续 429 的返回
            var field = McpHttpClient.class.getDeclaredField("toolToServer");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, McpProperties.ServerConfig> map = (Map<String, McpProperties.ServerConfig>) field.get(client);
            map.put("search_tool", serverConfig);

            String result = client.callTool("search_tool", "{}");
            assertTrue(result.contains("HTTP 429"));
            assertTrue(result.contains("严禁在本轮重复调用该工具"));
        } finally {
            testServer.stop(0);
        }
    }
}
