package asia.creat.teamdocsbackend.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import io.github.cdimascio.dotenv.Dotenv;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实模型端点在线连通性、Tool Calling 与 TokenUsage 验证测试
 *
 * 读取项目根目录 .env 文件中的真实配置：
 * - AGENT_ENABLED
 * - AGENT_BASE_URL
 * - AGENT_API_KEY
 * - AGENT_MODEL_NAME
 *
 * 若未配置有效 API KEY 则自动跳过，不阻断常规离线构建。
 */
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
    }

    private OpenAiChatModel buildModel(String customBaseUrl) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName != null && !modelName.isBlank() ? modelName : "gpt-3.5-turbo")
                .timeout(Duration.ofSeconds(60))
                .maxRetries(0)
                .logRequests(false)
                .logResponses(false);

        if (customBaseUrl != null && !customBaseUrl.isBlank()) {
            builder.baseUrl(customBaseUrl);
        }
        return builder.build();
    }

    @Test
    @DisplayName("测试真实模型基础对话与 TokenUsage 返回")
    void testRealModelTextGeneration() {
        if (!enabled || apiKey == null || apiKey.isBlank()) {
            System.out.println("[SKIP] 未配置有效 AGENT_API_KEY 或 AGENT_ENABLED != true，跳过真实模型在线测试");
            return;
        }

        // 处理 baseUrl 路径兼容（部分网关需要 /v1，部分直接提供）
        String targetUrl = baseUrl;
        if (targetUrl != null && !targetUrl.isBlank() && !targetUrl.contains("/v1")) {
            targetUrl = targetUrl.endsWith("/") ? targetUrl + "v1" : targetUrl + "/v1";
        }

        System.out.println("====== [Real Model Test] 开始测试基础文本生成 ======");
        System.out.println("目标模型: " + modelName);
        System.out.println("目标 BaseURL: " + targetUrl);

        long start = System.currentTimeMillis();
        OpenAiChatModel model = buildModel(targetUrl);

        Response<AiMessage> response = model.generate(List.of(UserMessage.from("你好！请严格只输出'收到'两个字。")));
        long elapsed = System.currentTimeMillis() - start;

        assertNotNull(response);
        assertNotNull(response.content());
        String text = response.content().text();
        System.out.println("模型回复文本: " + text);
        System.out.println("耗时: " + elapsed + " ms");

        TokenUsage usage = response.tokenUsage();
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
    void testRealModelToolCalling() {
        if (!enabled || apiKey == null || apiKey.isBlank()) {
            System.out.println("[SKIP] 未配置有效 AGENT_API_KEY，跳过真实模型工具调用测试");
            return;
        }

        String targetUrl = baseUrl;
        if (targetUrl != null && !targetUrl.isBlank() && !targetUrl.contains("/v1")) {
            targetUrl = targetUrl.endsWith("/") ? targetUrl + "v1" : targetUrl + "/v1";
        }

        System.out.println("====== [Real Model Test] 开始测试原生 Tool Calling 协议兼容性 ======");
        System.out.println("目标模型: " + modelName);

        OpenAiChatModel model = buildModel(targetUrl);

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

        if (aiMessage.hasToolExecutionRequests()) {
            List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
            for (ToolExecutionRequest req : requests) {
                System.out.println("成功触发工具调用: ID=" + req.id() + ", Name=" + req.name() + ", Args=" + req.arguments());
                assertEquals("search_documents", req.name());
                assertNotNull(req.arguments());
                assertTrue(req.arguments().contains("数据库") || req.arguments().contains("备份"));
            }
        } else {
            System.out.println("模型直接输出了文本（未触发工具调用）: " + aiMessage.text());
        }

        TokenUsage usage = response.tokenUsage();
        if (usage != null) {
            System.out.println("Tool Calling Token 用量: input=" + usage.inputTokenCount()
                    + ", output=" + usage.outputTokenCount()
                    + ", total=" + usage.totalTokenCount());
        }
    }
}
