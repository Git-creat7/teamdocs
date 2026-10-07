package asia.creat.agent;

import asia.creat.agent.AgentData.*;
import asia.creat.config.AgentProperties;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.internal.Json;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** 限制上下文和模型调用次数，并记录实际 Token 用量。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentBudget {
    // 仅作本地近似计数，不发起 OpenAI 请求，也不假定它就是供应商的 tokenizer。
    private static final OpenAiTokenizer TOKENIZER = new OpenAiTokenizer("gpt-4o");

    private final AgentRepository mapper;
    private final AgentProperties properties;

    public static void requireConfigured(AgentProperties properties) {
        if (!properties.isEnabled() || !properties.isAllowDocumentEgress()) throw new AgentFailure("AI_NOT_AUTHORIZED");

        if (properties.getModelName() == null || properties.getModelName().isBlank()) throw new AgentFailure("AI_MODEL_NOT_CONFIGURED");
    }

    /** Step 5 预留最大生成空间和 1% 估算余量；未知模型不默认拥有 1M 窗口。 */
    public static int inputLimit(AgentProperties properties, String modelName) {
        if (properties.getMaxInputTokens() > 0) return properties.getMaxInputTokens();
        if (!"step-5-preview".equalsIgnoreCase(modelName)) return 16000;

        int output = properties.getMaxOutputTokens() > 0
                ? Math.min(64000, properties.getMaxOutputTokens()) : 64000;
        return 1_000_000 - output - 10000;
    }

    /** 本地 Token 估算包含消息、工具定义和协议开销，不作为供应商真实用量的上限。 */
    public static int estimate(List<ChatMessage> messages) {
        return estimate(messages, AgentTools.defaultSpecifications());
    }

    public static int estimate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        long tokens = 128L + TOKENIZER.estimateTokenCountInText(Json.toJson(toolSpecifications));
        for (ChatMessage message : messages) tokens += estimateMessage(message);
        return (int) Math.min(Integer.MAX_VALUE, tokens);
    }

    /** 单条消息可加性计数，历史分页和裁剪不反复编码整个窗口。 */
    static int estimateMessage(ChatMessage message) {
        long tokens = 8L + TOKENIZER.estimateTokenCountInText(ChatMessageSerializer.messagesToJson(List.of(message)));
        if (message instanceof AttachmentMessage attachment) {
            for (AttachmentMessage.Part part : attachment.parts()) {
                if (part.mime().startsWith("image/")) {
                    // 不假定不同供应商采用相同图片计数方式，暂沿用保守额度。
                    tokens += part.base64().length() + 2048L;
                } else {
                    String text = new String(Base64.getDecoder().decode(part.base64()), StandardCharsets.UTF_8);
                    tokens += TOKENIZER.estimateTokenCountInText(text)
                            + TOKENIZER.estimateTokenCountInText(part.name()) + 32L;
                }
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, tokens);
    }

    /** 校准只属于本次运行，不跨用户或模型共享；低估时为下一轮补足容量。 */
    public static class InputEstimate {
        private double factor = 1;

        public int adjusted(int localEstimate) {
            return (int) Math.min(Integer.MAX_VALUE, Math.ceil(localEstimate * factor));
        }

        public int estimate(List<ChatMessage> messages, List<ToolSpecification> specifications) {
            return adjusted(AgentBudget.estimate(messages, specifications));
        }

        public void observe(int estimatedInput, Integer actualInput) {
            if (actualInput != null && actualInput > estimatedInput && estimatedInput > 0) {
                factor *= (double) actualInput / estimatedInput;
            }
        }
    }

    @Transactional
    public ModelCall startCall(Run supplied, int input) {
        if (mapper.lockUser(supplied.getUserId()) == null) throw new AgentFailure("ACCESS_REVOKED");

        Run run = mapper.lockRun(supplied.getId());

        if (run == null || !"RUNNING".equals(run.getStatus())) throw new AgentFailure("RUN_STOPPED");

        if (run.getModelConfigCiphertext() == null) requireConfigured(properties);
        else if (Boolean.FALSE.equals(properties.getEnabled()) || Boolean.FALSE.equals(properties.getAllowDocumentEgress())) {
            throw new AgentFailure("AI_NOT_AUTHORIZED");
        }

        if (run.getDeadlineMs() <= System.currentTimeMillis()) throw new AgentFailure("RUN_TIMEOUT");

        if (input > run.getMaxInputTokens()) {
            log.warn("AgentBudget 上下文超出限制: runId={}, estimatedInput={}, maxInputTokens={}", run.getId(), input, run.getMaxInputTokens());

            throw new AgentFailure("CONTEXT_LIMIT");
        }

        if (run.getModelCalls() >= run.getMaxModelCalls()) {
            log.warn("AgentBudget 模型调用次数已达上限: runId={}, currentCalls={}, maxCalls={}", run.getId(), run.getModelCalls(), run.getMaxModelCalls());

            throw new AgentFailure("MODEL_CALL_LIMIT");
        }

        ModelCall call = ModelCall.builder()
                .runId(run.getId())
                .userId(run.getUserId())
                .sequence(run.getModelCalls() + 1)
                .estimatedInput(input)
                .maxOutput(run.getMaxOutputTokens())
                .build();

        if (!mapper.nextModel(run.getId(), System.currentTimeMillis())) throw new AgentFailure("RUN_STOPPED");

        mapper.insertModelCall(call);
        log.debug("AgentBudget 开启模型调用: runId={}, sequence={}, estimatedInput={}", run.getId(), call.getSequence(), input);

        return call;
    }

    @Transactional
    public boolean recordUsage(ModelCall call, TokenUsage usage) {
        if (usage == null || usage.inputTokenCount() == null || usage.outputTokenCount() == null) return true;

        if (usage.inputTokenCount() < 0 || usage.outputTokenCount() < 0) throw new AgentFailure("MODEL_USAGE_INVALID");

        call.setInputTokens(usage.inputTokenCount().longValue());
        call.setOutputTokens(usage.outputTokenCount().longValue());

        if (mapper.lockUser(call.getUserId()) == null) throw new AgentFailure("ACCESS_REVOKED");

        mapper.recordModelUsage(call);

        // 0 表示这次未向供应商指定输出上限，实际用量仍正常记录。
        boolean withinLimits = call.getMaxOutput() <= 0 || call.getOutputTokens() <= call.getMaxOutput();

        if (!withinLimits) {
            log.warn("AgentBudget Token用量超出限额: runId={}, inputTokens={}/{}, outputTokens={}/{}",
                    call.getRunId(), call.getInputTokens(), call.getEstimatedInput(), call.getOutputTokens(), call.getMaxOutput());
        }

        return withinLimits;
    }
}
