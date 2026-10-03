package asia.creat.agent;

import asia.creat.agent.AgentData.Dependency;
import asia.creat.agent.AgentData.ReasoningProgress;
import asia.creat.agent.AgentData.Run;
import asia.creat.mapper.AgentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 思考只存短期有界内存；数据库只用于核对运行状态，不存思考内容。 */
@Component
@RequiredArgsConstructor
public class AgentReasoningRegistry {
    private static final int MAX_RUNS = 32;
    private final AgentMapper mapper;
    private final AgentEventHub events;
    private final Map<Long, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    public record Entry(Long userId, Long spaceId, Long sessionId, ReasoningProgress progress,
                        List<Dependency> dependencies, long expiresAt) { }

    /**
     * 在运行行锁保护下更新内存，避免取消后迟到分片写回。
     * @param run 当前运行
     * @param progress 累计思考
     * @param dependencies 产生思考时的资料依赖
     * @return 是否允许继续发布
     */
    @Transactional
    public boolean publish(Run run, ReasoningProgress progress, List<Dependency> dependencies) {
        Run current = mapper.lockRun(run.getId());
        long now = System.currentTimeMillis();
        if (current == null || !"RUNNING".equals(current.getStatus()) || current.getDeadlineMs() <= now
                || !Objects.equals(current.getUserId(), run.getUserId())
                || !Objects.equals(current.getSpaceId(), run.getSpaceId())) return false;
        if (progress.content() == null || progress.content().isBlank()) return true;
        if (progress.content().length() > 32768) throw new AgentFailure("REASONING_LIMIT");
        synchronized (entries) {
            entries.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
            entries.put(run.getId(), new Entry(run.getUserId(), run.getSpaceId(), run.getSessionId(), progress,
                    List.copyOf(dependencies), run.getDeadlineMs() + 60_000));
            while (entries.size() > MAX_RUNS) entries.remove(entries.keySet().iterator().next());
        }
        events.afterCommit(run.getId(), "reasoning_updated");
        return true;
    }

    /** 只返回与已授权运行一致且尚未过期的临时内容。 */
    public Entry get(Run run) {
        synchronized (entries) {
            Entry entry = entries.get(run.getId());
            if (entry == null) return null;
            if (entry.expiresAt() <= System.currentTimeMillis()) {
                entries.remove(run.getId());
                return null;
            }
            return Objects.equals(entry.userId(), run.getUserId())
                    && Objects.equals(entry.spaceId(), run.getSpaceId())
                    && Objects.equals(entry.sessionId(), run.getSessionId()) ? entry : null;
        }
    }

    /** 会话删除后立即丢弃相关运行的临时内容。 */
    public void forgetSession(Long sessionId) {
        synchronized (entries) {
            entries.entrySet().removeIf(entry -> Objects.equals(entry.getValue().sessionId(), sessionId));
        }
    }

    /** 到期后清理内容，服务重启也不会恢复。 */
    @Scheduled(fixedDelay = 30_000)
    public void expire() {
        synchronized (entries) {
            entries.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= System.currentTimeMillis());
        }
    }
}
