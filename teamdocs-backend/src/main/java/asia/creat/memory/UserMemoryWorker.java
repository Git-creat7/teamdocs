package asia.creat.memory;

import asia.creat.agent.model.OpenAiReasoningChatModel.RequestControl;
import asia.creat.mapper.UserMemoryJobMapper;
import asia.creat.memory.UserMemoryData.Job;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserMemoryWorker {
    private final UserMemoryJobMapper mapper;
    private final UserMemoryService service;
    private final UserMemoryExtractor extractor;
    private final AtomicReference<RequestControl> active = new AtomicReference<>();

    /** 串行处理一个到期任务，失败不影响问答结果。 */
    @Scheduled(fixedDelay = 2000, initialDelay = 5000, scheduler = "userMemoryTaskScheduler")
    public void processNext() {
        if (!extractor.canProcessJobs()) return;
        Job job = null;
        RequestControl control = new RequestControl();
        if (!active.compareAndSet(null, control)) return;

        try {
            long now = System.currentTimeMillis();
            job = mapper.next(now);
            if (job == null) return;
            String token = UUID.randomUUID().toString();
            if (!mapper.claim(job.getRunId(), now, now + 30_000, token)) return;
            job.setClaimToken(token);
            job.setAttempts(job.getAttempts() + 1);
            job = mapper.current(job.getRunId(), token, System.currentTimeMillis());
            if (job == null) return;

            var candidates = extractor.extractForRun(job.getRunId(), job.getQuestion(), service.contextItems(job.getUserId()), control);
            // 再校验租约、运行、用户授权与记忆代次；迟到结果绝不恢复删除的内容。
            service.apply(job, candidates);
        } catch (Exception error) {
            if (job != null && job.getClaimToken() != null) {
                try {
                    mapper.fail(job, System.currentTimeMillis() + 30_000);
                } catch (RuntimeException ignored) {
                    log.warn("记忆任务失败状态未能保存，将在租约过期后恢复");
                }
            }
            log.warn("用户记忆抽取未完成: {}", error.getClass().getSimpleName());
        } finally {
            active.compareAndSet(control, null);
        }
    }

    /** 回收失效任务，分批清理过期终态记录。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000, scheduler = "userMemoryTaskScheduler")
    public void cleanup() {
        try {
            mapper.discardObsolete(System.currentTimeMillis());
            mapper.prune();
        } catch (RuntimeException error) {
            log.warn("记忆任务清理未完成，请确认数据库迁移已执行: {}", error.getClass().getSimpleName());
        }
    }

    /** 停机时取消在途请求，任务由租约机制恢复。 */
    @PreDestroy
    public void stop() {
        RequestControl control = active.get();
        if (control != null) control.cancel();
    }
}
