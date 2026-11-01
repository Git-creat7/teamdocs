package asia.creat.retrieval;

import asia.creat.vo.ChunkHitVO;

import java.sql.SQLTimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 将运行截止时间、取消检查和出站来源记录传入检索调用。 */
public final class RetrievalContext implements AutoCloseable {
    private static final ThreadLocal<RetrievalContext> CURRENT = new ThreadLocal<>();

    private final RetrievalContext previous;
    private final long deadlineMs;
    private final Runnable checkpoint;
    private final Consumer<ChunkHitVO> dependency;
    private boolean inCheckpoint;

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

    /**
     * 将当前检索约束传播到子线程任务，并绑定共享取消标志。
     * 异步子线程禁止直接修改主线程依赖集合，依赖必须随异步结果返回主线程统一合并。
     * @param task 待执行任务
     * @param cancelled 共享取消标志检查
     * @return 包装后的任务
     * @param <T> 返回值类型
     */
    public static <T> Supplier<T> wrap(Supplier<T> task, BooleanSupplier cancelled) {
        RetrievalContext current = CURRENT.get();
        long deadline = current != null ? current.deadlineMs : Long.MAX_VALUE;
        Runnable parentCheckpoint = current != null ? current.checkpoint : () -> { };

        Runnable combinedCheckpoint = () -> {
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw new IllegalStateException("检索已取消");
            }
            parentCheckpoint.run();
        };

        Consumer<ChunkHitVO> forbiddenAsyncRecord = hit -> {
            throw new IllegalStateException("异步检索线程禁止直接修改主线程依赖集合");
        };

        return () -> {
            try (RetrievalContext scope = new RetrievalContext(deadline, combinedCheckpoint, forbiddenAsyncRecord)) {
                check();
                return task.get();
            }
        };
    }

    /** 检查本轮是否仍可执行，取消和超时不能作为远端故障吞掉。支持重入保护，避免 checkpoint 内部调用时发生死循环。 */
    public static void check() {
        RetrievalContext context = CURRENT.get();

        if (context != null) {
            if (!context.inCheckpoint) {
                context.inCheckpoint = true;
                try {
                    context.checkpoint.run();
                } finally {
                    context.inCheckpoint = false;
                }
            }

            if (System.currentTimeMillis() >= context.deadlineMs) {
                throw new IllegalStateException("检索运行已超时");
            }
        }
    }

    /** 判断当前线程是否绑定了有截止时间的检索上下文。 */
    public static boolean hasBoundedDeadline() {
        RetrievalContext context = CURRENT.get();
        return context != null && context.deadlineMs < Long.MAX_VALUE;
    }

    /** 获取当前运行的剩余时间（毫秒），未绑定时返回 Long.MAX_VALUE。 */
    public static long remainingMillis() {
        RetrievalContext context = CURRENT.get();
        if (context == null) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, context.deadlineMs - System.currentTimeMillis());
    }

    /** 获取当前运行的截止时间戳。 */
    public static long deadlineMs() {
        RetrievalContext context = CURRENT.get();
        return context != null ? context.deadlineMs : Long.MAX_VALUE;
    }

    /**
     * 将单次调用超时限制在当前运行的剩余时间内；当剩余预算 <= 0 时立即抛出超时异常。
     * @param configuredMillis 配置超时
     * @return 实际超时毫秒数（严格 > 0）
     */
    public static long timeoutMillis(long configuredMillis) {
        check();
        if (configuredMillis <= 0) {
            throw new IllegalStateException("检索运行已超时");
        }

        RetrievalContext context = CURRENT.get();
        long bounded = Math.min(30_000L, configuredMillis);

        if (context == null) {
            return bounded;
        }

        long remaining = context.deadlineMs - System.currentTimeMillis();
        if (remaining <= 0) {
            throw new IllegalStateException("检索运行已超时");
        }

        return Math.min(bounded, remaining);
    }

    /**
     * 在当前检索剩余时间预算内执行 JDBC 查询，超时或预算耗尽时立即中止并转换为超时异常。
     */
    public static <T> T executeJdbc(Supplier<T> query) {
        timeoutMillis(2000L);
        try {
            T result = query.get();
            check();
            return result;
        } catch (RuntimeException e) {
            check();
            if (isSqlTimeout(e)) {
                throw new IllegalStateException("检索运行已超时", e);
            }
            throw e;
        }
    }

    private static boolean isSqlTimeout(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof SQLTimeoutException) {
                return true;
            }
            String className = cursor.getClass().getSimpleName();
            if (className.contains("Timeout") || className.contains("MySQLTimeout")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
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
