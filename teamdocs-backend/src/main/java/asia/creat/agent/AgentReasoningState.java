package asia.creat.agent;

import asia.creat.agent.AgentData.Dependency;
import asia.creat.agent.AgentData.ReasoningProgress;
import asia.creat.agent.AgentData.Run;
import asia.creat.agent.model.OpenAiReasoningChatModel.ReasoningObserver;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 每轮独立累计思考，只将已提交快照通知前端。 */
public final class AgentReasoningState {
    private static final int MAX_CHARS = 32768;
    private final Run run;
    private final AgentReasoningRegistry registry;
    private final Supplier<List<Dependency>> dependencies;
    private final Runnable checkpoint;
    private final LongSupplier clock;
    private String content = "";
    private Long durationMs = 0L;
    private boolean truncated;
    private ReasoningProgress saved;
    private long lastWriteNanos;

    /** 创建按真实单调时钟节流的运行状态。 */
    public AgentReasoningState(Run run, AgentReasoningRegistry registry, Supplier<List<Dependency>> dependencies, Runnable checkpoint) {
        this(run, registry, dependencies, checkpoint, System::nanoTime);
    }

    /** 注入时钟以验证节流和轮次累计，不依赖测试休眠。 */
    public AgentReasoningState(Run run, AgentReasoningRegistry registry, Supplier<List<Dependency>> dependencies,
                               Runnable checkpoint, LongSupplier clock) {
        this.run = run;
        this.registry = registry;
        this.dependencies = dependencies;
        this.checkpoint = checkpoint;
        this.clock = clock;
    }

    /** 为本次模型调用建立独立的累计观察器，保留前面轮次。 */
    public synchronized ReasoningObserver beginCall() {
        String prefix = content.isEmpty() ? "" : content + "\n\n";
        Long previousDuration = durationMs;
        return (text, duration, cut) -> receive(prefix, previousDuration, text, duration, cut);
    }

    /** 合并当前调用的完整快照，避免把同一分片重复追加。 */
    private synchronized void receive(String prefix, Long previousDuration, String text, Long duration, boolean cut) {
        if (text == null || text.isBlank()) return;
        String complete = prefix + text;
        int end = Math.min(MAX_CHARS, complete.length());
        if (end < complete.length() && end > 0 && Character.isHighSurrogate(complete.charAt(end - 1))) end--;
        content = complete.substring(0, end);
        truncated |= cut || complete.length() > MAX_CHARS;
        durationMs = previousDuration == null || duration == null ? null : previousDuration + duration;
        flush(false);
    }

    /** 模型调用正常结束时提交最后一批文本，不等待节流窗口。 */
    public synchronized void flush() {
        flush(true);
    }

    /** 检查状态后保存进度，取消和期限检查仍以事务内运行状态为准。 */
    private void flush(boolean force) {
        if (content.isBlank()) return;
        long now = clock.getAsLong();
        ReasoningProgress current = snapshot();
        if (current.equals(saved) || (!force && saved != null
                && now - lastWriteNanos < TimeUnit.MILLISECONDS.toNanos(120))) return;
        checkpoint.run();
        if (!registry.publish(run, current, dependencies.get())) throw new AgentFailure("RUN_STOPPED");
        saved = current;
        lastWriteNanos = now;
    }

    /** 返回供最终消息使用的同一份有界内容，未知耗时保持为空。 */
    public synchronized ReasoningProgress snapshot() {
        return new ReasoningProgress(content.isBlank() ? null : content, content.isBlank() ? null : durationMs, truncated);
    }
}
