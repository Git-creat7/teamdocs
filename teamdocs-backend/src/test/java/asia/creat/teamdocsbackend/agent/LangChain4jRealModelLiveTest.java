package asia.creat.teamdocsbackend.agent;

import asia.creat.config.AgentModelConfiguration;
import asia.creat.config.AgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import io.github.cdimascio.dotenv.Dotenv;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实模型端点在线连通性、Tool Calling 与 TokenUsage 验证测试
 *
 * 读取项目根目录 .env 文件中的真实配置：
 * - AGENT_ENABLED
 * - AGENT_BASE_URL
 * - AGENT_API_KEY
 * - AGENT_MODEL_NAME
 *
 * 使用 -Dteamdocs.live-model=true 显式启用，常规 CI 不调用外部模型。
 */
@EnabledIfSystemProperty(named = "teamdocs.live-model", matches = "true")
public class LangChain4jRealModelLiveTest {

    private static String apiKey;
    private static String baseUrl;
    private static String modelName;
    private static boolean enabled;

    @BeforeAll
    static void loadEnv() {
        Dotenv dotenv = null;
        File rootEnv = new File("../.env");
        if (rootEnv.exists()) {
            dotenv = Dotenv.configure().directory("..").ignoreIfMissing().load();
        } else {
            dotenv = Dotenv.configure().ignoreIfMissing().load();
        }

        apiKey = dotenv.get("AGENT_API_KEY", System.getenv("AGENT_API_KEY"));
        baseUrl = dotenv.get("AGENT_BASE_URL", System.getenv("AGENT_BASE_URL"));
        modelName = dotenv.get("AGENT_MODEL_NAME", System.getenv("AGENT_MODEL_NAME"));
        String enabledStr = dotenv.get("AGENT_ENABLED", System.getenv("AGENT_ENABLED"));
        enabled = Boolean.parseBoolean(enabledStr);
        assumeTrue(enabled && apiKey != null && !apiKey.isBlank()
                && modelName != null && !modelName.isBlank(), "未配置在线模型测试所需参数");
    }

    private ChatLanguageModel buildModel(String customBaseUrl) {
        AgentProperties properties = new AgentProperties();
        properties.setApiKey(apiKey);
        properties.setModelName(modelName);
        properties.setBaseUrl(customBaseUrl);
        return new AgentModelConfiguration(properties).chatLanguageModel();
    }

    @Test
    @DisplayName("测试真实模型基础对话与 TokenUsage 返回")
    void testRealModelTextGeneration() {
        // 使用与实际应用相同的地址，不在测试中补写路径。
        String targetUrl = baseUrl;

        System.out.println("====== [Real Model Test] 开始测试基础文本生成 ======");
        System.out.println("目标模型: " + modelName);
        System.out.println("目标 BaseURL: " + targetUrl);

        long start = System.currentTimeMillis();
        ChatLanguageModel model = buildModel(targetUrl);

        Response<AiMessage> response = model.generate(List.of(UserMessage.from("你好！请严格只输出'收到'两个字。")));
        long elapsed = System.currentTimeMillis() - start;

        assertNotNull(response);
        assertNotNull(response.content());
        String text = response.content().text();
        System.out.println("模型回复文本: " + text);
        System.out.println("耗时: " + elapsed + " ms");

        TokenUsage usage = response.tokenUsage();
        assertNotNull(usage, "模型必须返回 TokenUsage");
        if (usage != null) {
            System.out.println("Token 用量: input=" + usage.inputTokenCount()
                    + ", output=" + usage.outputTokenCount()
                    + ", total=" + usage.totalTokenCount());
            assertTrue(usage.totalTokenCount() > 0, "总 Token 数应大于 0");
        } else {
            System.out.println("[WARN] 当前提供商未在响应中返回 TokenUsage");
        }
        assertNotNull(text);
        assertFalse(text.isBlank());
    }

    @Test
    @DisplayName("测试真实模型对 OpenAI-compatible Tool Calling 的原生支持")
    void testRealModelToolCalling() throws Exception {
        String targetUrl = baseUrl;

        System.out.println("====== [Real Model Test] 开始测试原生 Tool Calling 协议兼容性 ======");
        System.out.println("目标模型: " + modelName);

        ChatLanguageModel model = buildModel(targetUrl);

        // 构造一个低层工具定义：search_documents
        ToolSpecification searchTool = ToolSpecification.builder()
                .name("search_documents")
                .description("根据关键词在当前团队知识库中搜索相关文档")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("keyword", "要检索的关键词")
                        .required("keyword")
                        .build())
                .build();

        UserMessage userMessage = UserMessage.from("帮我查找关于 '数据库备份与恢复规范' 的文档");

        long start = System.currentTimeMillis();
        Response<AiMessage> response = model.generate(List.of(userMessage), List.of(searchTool));
        long elapsed = System.currentTimeMillis() - start;

        assertNotNull(response);
        AiMessage aiMessage = response.content();
        assertNotNull(aiMessage);

        System.out.println("Tool Calling 耗时: " + elapsed + " ms");
        System.out.println("是否有工具调用请求: " + aiMessage.hasToolExecutionRequests());
        assertTrue(aiMessage.hasToolExecutionRequests(), "应返回原生工具调用，不能以普通文本代替");

        if (aiMessage.hasToolExecutionRequests()) {
            List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
            List<String> keywords = new ArrayList<>();
            for (ToolExecutionRequest req : requests) {
                System.out.println("成功触发工具调用: ID=" + req.id() + ", Name=" + req.name() + ", Args=" + req.arguments());
                assertEquals("search_documents", req.name());
                assertNotNull(req.arguments());
                JsonNode arguments = new ObjectMapper().readTree(req.arguments());
                assertTrue(arguments.path("keyword").isTextual());
                String keyword = arguments.path("keyword").asText();
                assertFalse(keyword.isBlank());
                keywords.add(keyword);
            }
            // 模型可能把问题拆成几次并行调用，比如单独查“数据恢复”，只要求至少一次查的是这个主题
            assertTrue(keywords.stream().anyMatch(k -> k.contains("数据库") || k.contains("备份") || k.contains("恢复")));
        } else {
            System.out.println("模型直接输出了文本（未触发工具调用）: " + aiMessage.text());
        }

        TokenUsage usage = response.tokenUsage();
        assertNotNull(usage, "工具调用必须返回 TokenUsage");
        assertTrue(usage.totalTokenCount() > 0);
        if (usage != null) {
            System.out.println("Tool Calling Token 用量: input=" + usage.inputTokenCount()
                    + ", output=" + usage.outputTokenCount()
                    + ", total=" + usage.totalTokenCount());
        }
    }
}
