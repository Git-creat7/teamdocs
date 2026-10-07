package asia.creat.retrieval;

import asia.creat.vo.ChunkHitVO;

import java.util.function.Consumer;

/** 将运行截止时间、取消检查和出站来源记录传入检索调用。 */
public final class RetrievalContext implements AutoCloseable {
    private static final ThreadLocal<RetrievalContext> CURRENT = new ThreadLocal<>();

    private final RetrievalContext previous;
    private final long deadlineMs;
    private final Runnable checkpoint;
    private final Consumer<ChunkHitVO> dependency;

    /** 保存外层上下文并绑定本轮检索约束。 */
    private RetrievalContext(long deadlineMs, Runnable checkpoint, Consumer<ChunkHitVO> dependency) {
        this.previous = CURRENT.get();
        this.deadlineMs = deadlineMs;
        this.checkpoint = checkpoint;
        this.dependency = dependency;
        CURRENT.set(this);
    }

    /**
     * 创建一次检索范围，关闭时清理线程状态。
     * @param deadlineMs 截止时间
     * @param checkpoint 取消与权限检查
     * @param dependency 出站片段记录器
     * @return 可关闭的检索上下文
     */
    public static RetrievalContext open(long deadlineMs, Runnable checkpoint, Consumer<ChunkHitVO> dependency) {
        return new RetrievalContext(deadlineMs, checkpoint, dependency);
    }

    /** 检查本轮是否仍可执行，取消和超时不能作为远端故障吞掉。 */
    public static void check() {
        RetrievalContext context = CURRENT.get();

        if (context != null) {
            context.checkpoint.run();

            if (System.currentTimeMillis() >= context.deadlineMs) {
                throw new IllegalStateException("检索运行已超时");
            }
        }
    }

    /**
     * 将单次调用超时限制在当前运行的剩余时间内。
     * @param configuredMillis 配置超时
     * @return 实际超时毫秒数
     */
    public static long timeoutMillis(long configuredMillis) {
        check();

        RetrievalContext context = CURRENT.get();
        long bounded = Math.max(1, Math.min(30_000, configuredMillis));

        if (context == null) {
            return bounded;
        }

        return Math.max(1, Math.min(bounded, context.deadlineMs - System.currentTimeMillis()));
    }

    /**
     * 记录即将发送到外部模型的片段依赖。
     * @param hit 已复核片段
     */
    public static void record(ChunkHitVO hit) {
        check();

        RetrievalContext context = CURRENT.get();

        if (context != null) {
            context.dependency.accept(hit);
        }
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
