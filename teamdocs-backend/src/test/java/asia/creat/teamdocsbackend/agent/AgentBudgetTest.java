package asia.creat.teamdocsbackend.agent;

import asia.creat.agent.AgentBudget;
import asia.creat.agent.AgentData.ModelCall;
import asia.creat.agent.AgentFailure;
import asia.creat.agent.AgentRepository;
import asia.creat.agent.AttachmentMessage;
import asia.creat.config.AgentProperties;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentBudgetTest {
    private final AgentRepository repository = mock(AgentRepository.class);
    private final AgentBudget budget = new AgentBudget(repository, new AgentProperties());

    private ModelCall call(int maxOutput) {
        when(repository.lockUser(7L)).thenReturn(7L);
        return ModelCall.builder().runId(1L).userId(7L).estimatedInput(100).maxOutput(maxOutput).build();
    }

    @Test
    void unlimitedOutputStillRecordsActualUsage() {
        ModelCall call = call(0);
        assertTrue(budget.recordUsage(call, new TokenUsage(50, 12000)));
        assertEquals(12000L, call.getOutputTokens());
        verify(repository).recordModelUsage(call);
    }

    @Test
    void inputEstimateIsNotAHardUsageLimitButNegativeUsageIsRejected() {
        ModelCall call = call(0);
        assertTrue(budget.recordUsage(call, new TokenUsage(101, 12000)));
        assertEquals(101L, call.getInputTokens());
        assertThrows(AgentFailure.class, () -> budget.recordUsage(call, new TokenUsage(1, -1)));
    }

    @Test
    void explicitOutputBudgetIsStillRespected() {
        ModelCall call = call(8192);
        assertTrue(budget.recordUsage(call, new TokenUsage(10, 8192)));
        assertFalse(budget.recordUsage(call, new TokenUsage(10, 8193)));
    }

    @Test
    void stepWindowReservesGenerationAndSmallEstimationMarginNotOneThird() {
        var properties = new AgentProperties();
        properties.setModelName("different-system-model");
        assertEquals(926000, AgentBudget.inputLimit(properties, "step-5-preview"));
        assertEquals(0, properties.getMaxOutputTokens());
        properties.setMaxOutputTokens(8000);
        assertEquals(982000, AgentBudget.inputLimit(properties, "step-5-preview"));
    }

    @Test
    void unknownModelsDoNotInheritTheStepWindowAndExplicitInputLimitWins() {
        var properties = new AgentProperties();
        assertEquals(16000, AgentBudget.inputLimit(properties, "other-model"));
        properties.setMaxInputTokens(100000);
        assertEquals(100000, AgentBudget.inputLimit(properties, "other-model"));
        assertEquals(100000, AgentBudget.inputLimit(properties, "step-5-preview"));
    }

    @Test
    void tokenEstimateIsNotUtf8ByteCountAndIncludesTools() {
        String text = "这是文档内容，用于测试长上下文计数。".repeat(1000);
        var messages = List.of(UserMessage.from(text));
        int count = AgentBudget.estimate(List.of(messages.get(0)), List.of());
        assertTrue(count > 0);
        assertTrue(count < text.getBytes(StandardCharsets.UTF_8).length);
        var tool = ToolSpecification.builder().name("search_documents").description(text).build();
        assertTrue(AgentBudget.estimate(List.of(messages.get(0)), List.of(tool)) > count);
        assertDoesNotThrow(() -> AgentBudget.estimate(List.of(UserMessage.from("<|endoftext|>")), List.of()));
    }

    @Test
    void calibrationAppliesOnlyToTheCurrentRunAndDoesNotDiscardValidUsage() {
        var estimate = new AgentBudget.InputEstimate();
        estimate.observe(100, 200);
        assertEquals(300, estimate.adjusted(150));
        estimate.observe(300, 600);
        assertEquals(600, estimate.adjusted(150));
        estimate.observe(600, 20);
        assertEquals(600, estimate.adjusted(150));
        assertEquals(150, new AgentBudget.InputEstimate().adjusted(150));
        assertEquals(Integer.MAX_VALUE, estimate.adjusted(Integer.MAX_VALUE));
    }

    @Test
    void extractedAttachmentTextIsCountedAsTextRatherThanBase64Bytes() {
        String text = "文档内容用于问答。".repeat(1000);
        String data = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        var attachment = new AttachmentMessage("read", List.of(new AttachmentMessage.Part("a.txt", "text/plain", data)));
        int count = AgentBudget.estimate(List.of(attachment), List.of());
        assertTrue(count > AgentBudget.estimate(List.of(UserMessage.from("read")), List.of()));
        assertTrue(count < data.length());
    }
}
