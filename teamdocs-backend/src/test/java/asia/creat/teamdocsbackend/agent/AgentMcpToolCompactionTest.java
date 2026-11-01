package asia.creat.teamdocsbackend.agent;

import asia.creat.agent.AgentBudget;
import asia.creat.agent.AgentFailure;
import asia.creat.agent.AgentJson;
import asia.creat.agent.AgentTools;
import asia.creat.agent.AgentWorker;
import asia.creat.agent.mcp.McpHttpClient;
import asia.creat.config.AgentProperties;
import asia.creat.mapper.CommentMapper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.service.DocumentChunkQueryService;
import asia.creat.service.DocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AgentMcpToolCompactionTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentJson agentJson = new AgentJson(mapper);
    private final AgentTools tools = new AgentTools(
            mock(DocumentService.class), mock(DocumentChunkQueryService.class),
            mock(DocumentMapper.class), mock(CommentMapper.class),
            new AgentProperties(), agentJson, mock(McpHttpClient.class)
    );

    @Test
    void compactsTavilySearchResultsWhenExceedingMaxChars() {
        // 构造一个包含 10 个条目的大型 Tavily 响应（模拟 20000+ 字符）
        StringBuilder resultsBuilder = new StringBuilder("[");
        for (int i = 0; i < 10; i++) {
            if (i > 0) resultsBuilder.append(",");
            resultsBuilder.append("{\"title\":\"周杰伦演唱会第").append(i).append("站\",")
                    .append("\"url\":\"https://example.com/tour/").append(i).append("\",")
                    .append("\"content\":\"").append("非常冗长的新闻正文，包含很多背景信息和介绍。".repeat(80)).append("\",")
                    .append("\"score\":0.95}");
        }
        resultsBuilder.append("]");
        String rawTavilyJson = "{\"query\":\"周杰伦演唱会\",\"results\":" + resultsBuilder + "}";

        assertTrue(rawTavilyJson.length() > 10000);

        String compacted = tools.shortenMcpResult(rawTavilyJson, 4000);

        assertTrue(compacted.length() <= 4000);
        assertTrue(compacted.contains("\"truncated\":true"));
        assertTrue(compacted.contains("周杰伦演唱会第0站"));
    }

    @Test
    void truncatesArbitraryOversizedMcpResultAtLimit() {
        String longText = "这是一段非常长的外部代码或输出。".repeat(600); // ~9600 字符
        assertTrue(longText.length() > 5000);

        String result = tools.shortenMcpResult(longText, 4000);

        assertTrue(result.length() <= 4100);
        assertTrue(result.contains("外部工具返回内容过长，已截断保留前 4000 字符"));
    }

    @Test
    void preservesSmallMcpResultIntact() {
        String small = "{\"result\":\"ok\"}";
        assertSame(small, tools.shortenMcpResult(small, 4000));
    }

    @Test
    void trimHistoryToBudgetTruncatesCurrentRoundToolResultsInsteadOfThrowingContextLimit() {
        AgentWorker worker = org.mockito.Mockito.mock(AgentWorker.class, org.mockito.Mockito.CALLS_REAL_METHODS);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("你是一个助手"));
        messages.add(UserMessage.from("周杰伦演唱会"));

        // 构造两个各 8000 字符的工具结果消息（如果直接估算会超过 10000 预算）
        String giantText1 = "演唱会详细资料1: ".repeat(600);
        String giantText2 = "演唱会详细资料2: ".repeat(600);
        messages.add(ToolExecutionResultMessage.from("call_1", "tavily_search", giantText1));
        messages.add(ToolExecutionResultMessage.from("call_2", "tavily_search", giantText2));

        List<ToolSpecification> specs = List.of();
        AgentBudget.InputEstimate estimate = new AgentBudget.InputEstimate();

        int maxInput = 6000;
        int remainingPairs = ReflectionTestUtils.invokeMethod(worker, "trimHistoryToBudget",
                messages, 0, maxInput, specs, estimate);

        assertEquals(0, remainingPairs);
        // 验证没有抛出 CONTEXT_LIMIT 异常，并且工具结果被成功自适应截断以满足预算
        assertTrue(estimate.adjusted(AgentBudget.estimate(messages, specs)) <= maxInput);
        ToolExecutionResultMessage result1 = (ToolExecutionResultMessage) messages.get(2);
        assertTrue(result1.text().contains("[内容已截断以适应上下文上限]"));
    }
}
