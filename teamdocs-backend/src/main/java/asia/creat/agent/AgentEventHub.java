package asia.creat.agent;

import asia.creat.agent.AgentData.RunView;
import asia.creat.common.exception.BusinessException;
import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** 只观察运行。缓冲的是失效通知，不缓存答案；每次发送都重新读取并授权。 */
@Slf4j
public class AgentEventHub {
    private static final int MAX_CONNECTIONS = 32;
    private static final int MAX_PER_USER = 2;
    private static final int MAX_PENDING = 32;

    private final ExecutorService sender;
    private final Object gate = new Object();
    private final Map<Long, Set<Subscription>> subscriptions = new HashMap<>();
    private long revision;

    public AgentEventHub(ExecutorService sender) { this.sender = sender; }

    public record Event(Long spaceId, Long sessionId, Long runId, long sequence, RunView snapshot, String errorCode) { }

    private record Signal(long revision, String type) { }

    public SseEmitter subscribe(Long spaceId, Long sessionId, Long runId, Long userId, Supplier<RunView> snapshot) {
        log.info("Agent SSE 建立连接: spaceId={}, sessionId={}, runId={}, userId={}", spaceId, sessionId, runId, userId);

        Subscription subscription = new Subscription(spaceId, sessionId, runId, userId, snapshot);

        synchronized (gate) {
            List<Subscription> all = all();

            if (all.size() >= MAX_CONNECTIONS || all.stream().filter(s -> s.userId.equals(userId)).count() >= MAX_PER_USER) {
                log.warn("Agent SSE 连接超出限制: userId={}, currentAll={}", userId, all.size());

                throw new BusinessException("问答连接过多，请关闭其他页面后重试");
            }

            subscriptions.computeIfAbsent(runId, ignored -> new LinkedHashSet<>()).add(subscription);
        }

        subscription.emitter.onCompletion(() -> subscription.close(false));
        subscription.emitter.onError(error -> subscription.close(false));
        subscription.emitter.onTimeout(() -> subscription.close(true));
        // 先注册，再异步加载快照；这期间的通知进入有界队列。
        subscription.schedule();

        return subscription.emitter;
    }

    public void publish(Long runId, String type) {
        log.debug("Agent SSE 发布事件: runId={}, type={}", runId, type);

        synchronized (gate) {
            Signal signal = new Signal(++revision, type);

            for (Subscription subscription : List.copyOf(subscriptions.getOrDefault(runId, Set.of()))) subscription.enqueue(signal);
        }
    }

    public void afterCommit(Long runId, String type) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish(runId, type); }
            });
        } else publish(runId, type);
    }

    @Scheduled(fixedDelay = 10000)
    public void refreshAll() {
        synchronized (gate) {
            for (Long runId : List.copyOf(subscriptions.keySet())) publish(runId, "snapshot");
        }
    }

    @PreDestroy
    public void close() {
        List<Subscription> current;

        synchronized (gate) { current = all(); }

        for (Subscription subscription : current) subscription.close(true);
    }

    private List<Subscription> all() {
        List<Subscription> result = new ArrayList<>();

        for (Set<Subscription> set : subscriptions.values()) result.addAll(set);

        return result;
    }

    private class Subscription {
        final Long spaceId, sessionId, runId, userId;
        final Supplier<RunView> loader;
        final SseEmitter emitter = new SseEmitter(120000L);
        final ArrayDeque<Signal> pending = new ArrayDeque<>();
        final AtomicBoolean sending = new AtomicBoolean();
        final AtomicBoolean completed = new AtomicBoolean();
        volatile boolean closed;
        boolean initialized;
        long sequence;

        Subscription(Long spaceId, Long sessionId, Long runId, Long userId, Supplier<RunView> loader) {
            this.spaceId = spaceId;
            this.sessionId = sessionId;
            this.runId = runId;
            this.userId = userId;
            this.loader = loader;
        }

        void enqueue(Signal signal) {
            if (closed) return;

            boolean overflow;

            synchronized (pending) {
                overflow = pending.size() >= MAX_PENDING;

                if (!overflow) pending.addLast(signal);
            }

            if (overflow) {
                // 慢连接不会阻塞 Agent 线程。发送线程结束当前写入后关闭，客户端重新取快照。
                close(false);

                return;
            }

            schedule();
        }

        void schedule() {
            if (closed || !sending.compareAndSet(false, true)) return;

            try { sender.execute(this::drain); }
            catch (RejectedExecutionException busy) { sending.set(false); close(true); }
        }

        void drain() {
            try {
                if (!initialized && !closed) {
                    long watermark;

                    synchronized (gate) { watermark = revision; }

                    RunView view = loader.get();

                    emit("snapshot", view, null);
                    initialized = true;

                    synchronized (pending) { pending.removeIf(signal -> signal.revision() <= watermark); }

                    if (terminal(view)) { close(true); return; }
                }

                while (!closed) {
                    Signal signal;

                    synchronized (pending) { signal = pending.pollFirst(); }

                    if (signal == null) break;

                    RunView view = loader.get();

                    if (terminal(view)) {
                        if (view.answer() != null && !view.answer().masked()
                                && view.answer().text() != null && !view.answer().text().isBlank()) {
                            emit("answer_ready", view, null);
                        }

                        emit(Set.of("FAILED", "TIMED_OUT").contains(view.status()) ? "run_failed" : "run_finished", view, null);
                        close(true);

                        return;
                    }

                    emit(signal.type(), view, null);
                }
            } catch (BusinessException denied) {
                streamError("ACCESS_REVOKED");
            } catch (IOException disconnected) {
                close(true);
            } catch (RuntimeException unavailable) {
                streamError("STREAM_UNAVAILABLE");
            } finally {
                sending.set(false);

                if (closed) close(true);
                else {
                    boolean more;

                    synchronized (pending) { more = !pending.isEmpty(); }

                    if (more) schedule();
                }
            }
        }

        void emit(String type, RunView view, String error) throws IOException {
            if (closed) return;

            long next = ++sequence;

            emitter.send(SseEmitter.event().name(type).id(Long.toString(next))
                    .data(new Event(spaceId, sessionId, runId, next, view, error), MediaType.APPLICATION_JSON));
        }

        void streamError(String code) {
            log.warn("Agent SSE 推送异常: runId={}, userId={}, code={}", runId, userId, code);

            try { emit("stream_error", null, code); }
            catch (IOException ignored) { }
            finally { close(true); }
        }

        void close(boolean finish) {
            log.debug("Agent SSE 关闭连接: runId={}, userId={}, finish={}", runId, userId, finish);
            closed = true;

            synchronized (gate) {
                Set<Subscription> set = subscriptions.get(runId);

                if (set != null) {
                    set.remove(this);

                    if (set.isEmpty()) subscriptions.remove(runId);
                }
            }

            synchronized (pending) { pending.clear(); }

            if (finish && completed.compareAndSet(false, true)) emitter.complete();
        }

        boolean terminal(RunView view) { return !Set.of("QUEUED", "RUNNING").contains(view.status()); }
    }
}
