package asia.creat.agent;

import asia.creat.agent.AgentData.*;
import asia.creat.config.AgentProperties;
import asia.creat.mapper.AgentMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.internal.Json;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 限制上下文和模型调用次数，并记录实际 Token 用量。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentBudget {
    private final AgentMapper mapper;
    private final AgentProperties properties;

    public static void requireConfigured(AgentProperties properties) {
        if (!properties.isEnabled() || !properties.isAllowDocumentEgress()) throw new AgentFailure("AI_NOT_AUTHORIZED");
        if (properties.getModelName() == null || properties.getModelName().isBlank()) throw new AgentFailure("AI_MODEL_NOT_CONFIGURED");
    }

    /** UTF-8 字节数是文本 token 的保守上界；包含完整历史、工具定义、参数及结果，另留协议开销。 */
    public static int estimate(List<ChatMessage> messages) {
        return estimate(messages, AgentTools.defaultSpecifications());
    }

    public static int estimate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        long bytes = ChatMessageSerializer.messagesToJson(messages).getBytes(StandardCharsets.UTF_8).length;
        bytes += Json.toJson(toolSpecifications).getBytes(StandardCharsets.UTF_8).length;
        bytes += 1024L + messages.size() * 64L;
        return (int) Math.min(Integer.MAX_VALUE, bytes);
    }

    @Transactional
    public ModelCall startCall(Run supplied, int input) {
        requireConfigured(properties);
        if (mapper.lockUser(supplied.getUserId()) == null) throw new AgentFailure("ACCESS_REVOKED");
        Run run = mapper.lockRun(supplied.getId());
        if (run == null || !"RUNNING".equals(run.getStatus())) throw new AgentFailure("RUN_STOPPED");
        if (run.getDeadlineMs() <= System.currentTimeMillis()) throw new AgentFailure("RUN_TIMEOUT");
        if (input > run.getMaxInputTokens()) {
            log.warn("AgentBudget 上下文超出限制: runId={}, estimatedInput={}, maxInputTokens={}", run.getId(), input, run.getMaxInputTokens());
            throw new AgentFailure("CONTEXT_LIMIT");
        }
        if (run.getModelCalls() >= run.getMaxModelCalls()) {
            log.warn("AgentBudget 模型调用次数已达上限: runId={}, currentCalls={}, maxCalls={}", run.getId(), run.getModelCalls(), run.getMaxModelCalls());
            throw new AgentFailure("MODEL_CALL_LIMIT");
        }
        ModelCall call = new ModelCall();
        call.setRunId(run.getId()); call.setUserId(run.getUserId()); call.setSequence(run.getModelCalls() + 1);
        call.setEstimatedInput(input); call.setMaxOutput(run.getMaxOutputTokens());
        if (mapper.nextModel(run.getId(), System.currentTimeMillis()) != 1) throw new AgentFailure("RUN_STOPPED");
        mapper.insertModelCall(call);
        log.debug("AgentBudget 开启模型调用: runId={}, sequence={}, estimatedInput={}", run.getId(), call.getSequence(), input);
        return call;
    }

    @Transactional
    public boolean recordUsage(ModelCall call, TokenUsage usage) {
        if (usage == null || usage.inputTokenCount() == null || usage.outputTokenCount() == null) return true;
        if (usage.inputTokenCount() < 0 || usage.outputTokenCount() < 0) throw new AgentFailure("MODEL_USAGE_INVALID");
        call.setInputTokens(usage.inputTokenCount().longValue()); call.setOutputTokens(usage.outputTokenCount().longValue());
        if (mapper.lockUser(call.getUserId()) == null) throw new AgentFailure("ACCESS_REVOKED");
        mapper.recordModelUsage(call);
        boolean withinLimits = call.getInputTokens() <= call.getEstimatedInput() && call.getOutputTokens() <= call.getMaxOutput();
        if (!withinLimits) {
            log.warn("AgentBudget Token用量超出限额: runId={}, inputTokens={}/{}, outputTokens={}/{}",
                    call.getRunId(), call.getInputTokens(), call.getEstimatedInput(), call.getOutputTokens(), call.getMaxOutput());
        }
        return withinLimits;
    }
}
