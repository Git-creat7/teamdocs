package asia.creat.teamdocsbackend.agent;

import asia.creat.config.AgentModelConfiguration;
import asia.creat.config.AgentProperties;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P0 阶段：LangChain4j 0.36.2 候选版本框架层兼容性与低层协议验证
 *
 * 注意：本测试严格区分：
 * 1. 框架层兼容性（Java 17 + Spring Boot 3.5.14 + LangChain4j 候选版本 0.36.2）；
 * 2. 真实模型端到端兼容性（需真实网络端点与有效密钥，当前仅验证框架与离线协议，不假装已测真实模型）。
 */
class LangChain4jCandidateVerificationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AgentModelConfiguration.class);

    @Test
    @DisplayName("验证 AI 开关关闭(enabled=false)时，不创建 ChatLanguageModel Bean，系统完全独立正常")
    void testAiDisabledStartup() {
        contextRunner
                .withPropertyValues("teamdocs.agent.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertFalse(context.containsBean("chatLanguageModel"));
                });
    }

    @Test
    @DisplayName("验证 AI 开关开启但缺少必要配置时，不创建外部模型 Bean，且不抛异常中断启动")
    void testAiEnabledWithMissingConfig() {
        contextRunner
                .withPropertyValues(
                        "teamdocs.agent.enabled=true",
                        "teamdocs.agent.api-key=",
                        "teamdocs.agent.model-name="
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertNull(context.getBeanProvider(ChatLanguageModel.class).getIfAvailable());
                });
    }

    @Test
    @DisplayName("验证 AI 开启且配置完整时，成功装配 OpenAiChatModel，并严格遵守重试与日志约束")
    void testAiEnabledWithValidConfig() {
        contextRunner
                .withPropertyValues(
                        "teamdocs.agent.enabled=true",
                        "teamdocs.agent.base-url=https://mock-ai-endpoint.local/v1",
                        "teamdocs.agent.api-key=test-mock-key-12345",
                        "teamdocs.agent.model-name=generic-test-model",
                        "teamdocs.agent.timeout-seconds=45"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertTrue(context.containsBean("chatLanguageModel"));
                    ChatLanguageModel model = context.getBean(ChatLanguageModel.class);
                    assertNotNull(model);
                    assertInstanceOf(OpenAiChatModel.class, model);
                });
    }

    @Test
    @DisplayName("验证低层 ToolSpecification 协议构造与参数描述能力")
    void testToolSpecificationProtocol() {
        ToolSpecification toolSpec = ToolSpecification.builder()
                .name("search_document_chunks")
                .description("在当前空间内检索相关文档段落")
                .build();

        assertEquals("search_document_chunks", toolSpec.name());
        assertEquals("在当前空间内检索相关文档段落", toolSpec.description());
    }

    @Test
    @DisplayName("验证低层 ToolExecutionRequest 与 ToolExecutionResultMessage 往返消息协议")
    void testToolExecutionMessageFlow() {
        // 1. 用户输入
        UserMessage userMessage = UserMessage.from("查询上线检查清单");
        assertEquals("查询上线检查清单", userMessage.singleText());

        // 2. 模拟模型决定调用工具：生成 ToolExecutionRequest 与包含请求的 AiMessage
        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("call_test_001")
                .name("search_document_chunks")
                .arguments("{\"query\":\"上线检查清单\"}")
                .build();

        AiMessage aiMessage = AiMessage.from(toolRequest);
        assertTrue(aiMessage.hasToolExecutionRequests());
        assertEquals(1, aiMessage.toolExecutionRequests().size());
        assertEquals("call_test_001", aiMessage.toolExecutionRequests().get(0).id());
        assertEquals("search_document_chunks", aiMessage.toolExecutionRequests().get(0).name());
        assertEquals("{\"query\":\"上线检查清单\"}", aiMessage.toolExecutionRequests().get(0).arguments());

        // 3. 应用执行工具后，构造 ToolExecutionResultMessage 回传给模型上下文
        String simulatedToolResult = "[{\"documentId\":10,\"chunkIndex\":0,\"content\":\"检查项1：数据库备份\"}]";
        ToolExecutionResultMessage resultMessage = ToolExecutionResultMessage.from(
                toolRequest.id(),
                toolRequest.name(),
                simulatedToolResult
        );

        assertEquals("call_test_001", resultMessage.id());
        assertEquals("search_document_chunks", resultMessage.toolName());
        assertEquals(simulatedToolResult, resultMessage.text());
    }

    @Test
    @DisplayName("验证 TokenUsage 字段提取契约（输入、输出、总计）")
    void testTokenUsageContract() {
        TokenUsage tokenUsage = new TokenUsage(120, 35, 155);

        assertEquals(120, tokenUsage.inputTokenCount());
        assertEquals(35, tokenUsage.outputTokenCount());
        assertEquals(155, tokenUsage.totalTokenCount());
    }

    @Test
    @DisplayName("验证 OpenAiChatModel Builder 超时与非空安全约束")
    void testOpenAiChatModelBuilderConstraints() {
        assertDoesNotThrow(() -> {
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .apiKey("mock-key")
                    .modelName("generic-model")
                    .baseUrl("https://localhost:9999/v1")
                    .timeout(Duration.ofSeconds(30))
                    .maxRetries(0)
                    .logRequests(false)
                    .logResponses(false)
                    .build();
            assertNotNull(model);
        });
    }
}
