package asia.creat.agent;

import asia.creat.agent.AgentData.*;
import asia.creat.config.AgentProperties;
import asia.creat.mapper.AgentMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.internal.Json;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentBudget {
    private final AgentMapper mapper;
    private final AgentProperties properties;

    public static void requireConfigured(AgentProperties properties) {
        if (!properties.isEnabled() || !properties.isAllowDocumentEgress()) throw new AgentFailure("AI_NOT_AUTHORIZED");
        if (properties.getModelName() == null || properties.getModelName().isBlank()
                || properties.getInputPricePerMillion() == null || properties.getInputPricePerMillion().signum() < 0
                || properties.getOutputPricePerMillion() == null || properties.getOutputPricePerMillion().signum() < 0
                || properties.getDailyBudget() == null || properties.getDailyBudget().signum() <= 0) {
            throw new AgentFailure("AI_BUDGET_NOT_CONFIGURED");
        }
    }

    /** UTF-8 字节数是文本 token 的保守上界；包含完整历史、工具定义、参数及结果，另留协议开销。 */
    public static int estimate(List<ChatMessage> messages) {
        long bytes = ChatMessageSerializer.messagesToJson(messages).getBytes(StandardCharsets.UTF_8).length;
        bytes += Json.toJson(AgentTools.specifications()).getBytes(StandardCharsets.UTF_8).length;
        bytes += 1024L + messages.size() * 64L;
        return (int) Math.min(Integer.MAX_VALUE, bytes);
    }

    @Transactional
    public Charge reserve(Run supplied, int input) {
        requireConfigured(properties);
        if (mapper.lockUser(supplied.getUserId()) == null) throw new AgentFailure("ACCESS_REVOKED");
        Run run = mapper.lockRun(supplied.getId());
        if (run == null || !"RUNNING".equals(run.getStatus())) throw new AgentFailure("RUN_STOPPED");
        if (run.getDeadlineMs() <= System.currentTimeMillis()) throw new AgentFailure("RUN_TIMEOUT");
        if (input > run.getMaxInputTokens()) throw new AgentFailure("CONTEXT_LIMIT");
        if (run.getModelCalls() >= run.getMaxModelCalls()) throw new AgentFailure("MODEL_CALL_LIMIT");
        Charge charge = new Charge();
        charge.setRunId(run.getId()); charge.setUserId(run.getUserId()); charge.setSequence(run.getModelCalls() + 1);
        charge.setEstimatedInput(input); charge.setMaxOutput(run.getMaxOutputTokens());
        charge.setInputPrice(properties.getInputPricePerMillion()); charge.setOutputPrice(properties.getOutputPricePerMillion());
        charge.setReservedCost(cost(input, run.getMaxOutputTokens(), charge));
        if (mapper.dailyCharged(run.getUserId()).add(charge.getReservedCost()).compareTo(properties.getDailyBudget()) > 0) {
            throw new AgentFailure("DAILY_BUDGET_EXCEEDED");
        }
        if (mapper.nextModel(run.getId(), System.currentTimeMillis()) != 1) throw new AgentFailure("RUN_STOPPED");
        mapper.insertCharge(charge);
        return charge;
    }

    @Transactional
    public boolean settle(Charge charge, TokenUsage usage) {
        if (usage == null || usage.inputTokenCount() == null || usage.outputTokenCount() == null) return true;
        if (usage.inputTokenCount() < 0 || usage.outputTokenCount() < 0) throw new AgentFailure("MODEL_USAGE_INVALID");
        charge.setInputTokens(usage.inputTokenCount().longValue()); charge.setOutputTokens(usage.outputTokenCount().longValue());
        charge.setChargedCost(cost(charge.getInputTokens(), charge.getOutputTokens(), charge));
        if (mapper.lockUser(charge.getUserId()) == null) throw new AgentFailure("ACCESS_REVOKED");
        mapper.settleCharge(charge);
        // 在事务提交后由调用方终止运行，不能因抛错回滚已经发生的用量。
        return charge.getInputTokens() <= charge.getEstimatedInput() && charge.getOutputTokens() <= charge.getMaxOutput();
    }

    private BigDecimal cost(long input, long output, Charge charge) {
        return charge.getInputPrice().multiply(BigDecimal.valueOf(input))
                .add(charge.getOutputPrice().multiply(BigDecimal.valueOf(output)))
                .divide(BigDecimal.valueOf(1000000), 8, RoundingMode.CEILING);
    }
}
