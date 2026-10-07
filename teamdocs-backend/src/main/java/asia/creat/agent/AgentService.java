package asia.creat.agent;

import asia.creat.agent.AgentData.*;
import asia.creat.anno.RequireSpaceRole;
import asia.creat.anno.SpaceId;
import asia.creat.common.PageResult;
import asia.creat.common.exception.BusinessException;
import asia.creat.dto.PageQuery;
import asia.creat.mapper.AnswerFeedbackMapper;
import asia.creat.security.LoginUser;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {
    private final AgentRepository mapper;
    private final AgentStore store;
    private final AgentWorker worker;
    private final AgentTools tools;
    private final AgentJson json;
    private final AgentEventHub events;
    private final ObjectProvider<AgentReasoningRegistry> reasoning;

    @Nullable
    private final AnswerFeedbackMapper feedback;

    @RequireSpaceRole
    public Session createSession(@SpaceId Long spaceId, NewSession request, LoginUser user) {
        log.info("AgentService 创建会话: spaceId={}, userId={}, title={}", spaceId, user.getUserId(), request.title());

        Session session = store.createSession(spaceId, user.getUserId(), request.title());

        log.info("AgentService 会话创建成功: sessionId={}, spaceId={}", session.getId(), spaceId);

        return session;
    }

    @RequireSpaceRole
    public PageResult<Session> sessions(@SpaceId Long spaceId, PageQuery page, LoginUser user) {
        long offset = offset(page);
        long total = mapper.countSessions(spaceId, user.getUserId());

        return page(mapper.sessions(spaceId, user.getUserId(), offset, page.getSize()), total, page);
    }

    @Transactional
    @RequireSpaceRole
    public void deleteSession(@SpaceId Long spaceId, Long sessionId, LoginUser user) {
        log.info("AgentService 删除会话: spaceId={}, sessionId={}, userId={}", spaceId, sessionId, user.getUserId());

        // 与创建运行共用用户行锁，避免删除期间向会话写入新的运行。
        if (mapper.lockUser(user.getUserId()) == null) {
            throw new BusinessException("用户不可用");
        }

        requireSession(spaceId, sessionId, user);

        for (Run run : mapper.sessionRuns(sessionId)) {
            if ("QUEUED".equals(run.getStatus()) || "RUNNING".equals(run.getStatus())) {
                throw new BusinessException("会话仍有运行中的任务，请先停止再删除");
            }

            // 取消状态可能已落库，但执行线程尚未退出，不能留下迟到的孤立记录。
            if (worker.isExecuting(run.getId())) {
                throw new BusinessException("任务正在结束，请稍后再删除会话");
            }
        }

        mapper.deleteSessionModelCalls(sessionId);
        mapper.deleteSessionToolCalls(sessionId);
        if (feedback != null) {
            var runIds = mapper.sessionRuns(sessionId).stream().map(Run::getId).toList();
            if (!runIds.isEmpty()) feedback.deleteByIds(runIds);
        }
        mapper.deleteSessionMessages(sessionId);
        mapper.deleteSessionRuns(sessionId);
        mapper.deleteSession(sessionId, spaceId, user.getUserId());

        reasoning.ifAvailable(registry -> registry.forgetSession(sessionId));
        log.info("AgentService 会话删除成功: spaceId={}, sessionId={}", spaceId, sessionId);
    }

    @RequireSpaceRole
    public Long submit(@SpaceId Long spaceId, Long sessionId, NewRun request, LoginUser user) {
        log.info("AgentService 接收提交提问: spaceId={}, sessionId={}, userId={}, clientRequestId={}",
                spaceId, sessionId, user.getUserId(), request.clientRequestId());
        requireSession(spaceId, sessionId, user);

        if (mapper.existingRun(sessionId, request.clientRequestId()) == null && !worker.available(user.getUserId())) {
            log.warn("AgentService 提交失败: AI执行服务尚未就绪或模型未配置, spaceId={}, sessionId={}", spaceId, sessionId);

            throw new BusinessException("AI 执行服务尚未就绪或模型未配置");
        }

        try {
            AgentStore.Created created = store.createRun(spaceId, sessionId, user.getUserId(), request);

            if (created.created()) {
                log.info("AgentService 任务入队: runId={}, spaceId={}, sessionId={}", created.run().getId(), spaceId, sessionId);
                worker.enqueue(created.run().getId(), user);
            } else {
                log.info("AgentService 命中幂等请求，返回已有运行: runId={}, spaceId={}, sessionId={}", created.run().getId(), spaceId, sessionId);
            }

            return created.run().getId();
        } catch (AgentFailure e) {
            log.error("AgentService 创建运行失败: spaceId={}, sessionId={}, error={}", spaceId, sessionId, e.code(), e);

            throw new BusinessException(e.code());
        }
    }

    @RequireSpaceRole
    public PageResult<MessageView> messages(@SpaceId Long spaceId, Long sessionId, PageQuery page, LoginUser user) {
        requireSession(spaceId, sessionId, user);

        long offset = offset(page);
        List<MessageView> records = mapper.messages(sessionId, offset, page.getSize()).stream()
                .map(message -> visible(spaceId, user, message)).toList();

        return page(records, mapper.countMessages(sessionId), page);
    }

    @RequireSpaceRole
    public RunView run(@SpaceId Long spaceId, Long runId, LoginUser user) {
        return snapshot(spaceId, user, ownedRun(spaceId, runId, user));
    }

    @RequireSpaceRole
    public RunView cancel(@SpaceId Long spaceId, Long runId, LoginUser user) {
        log.info("AgentService 请求取消运行: spaceId={}, runId={}, userId={}", spaceId, runId, user.getUserId());
        ownedRun(spaceId, runId, user);

        if (mapper.endActive(runId, "CANCELLED", "USER_CANCELLED")) {
            log.info("AgentService 运行已标记取消: runId={}", runId);
            worker.cancelModel(runId);
            events.publish(runId, "run_finished");
        }

        return snapshot(spaceId, user, mapper.run(runId));
    }

    private RunView snapshot(Long spaceId, LoginUser user, Run run) {
        long input = 0, output = 0;
        boolean unknown = false;

        for (ModelCall call : mapper.modelCalls(run.getId())) {
            input += call.getInputTokens() == null ? 0 : call.getInputTokens();
            output += call.getOutputTokens() == null ? 0 : call.getOutputTokens();
            unknown |= !call.isUsageKnown();
        }

        MessageView answer = runtimeAnswer(spaceId, user, run, visible(spaceId, user, mapper.answer(run.getId())));

        return new RunView(run.getId(), run.getSessionId(), run.getStatus(), run.getErrorCode(), run.getModelCalls(), run.getToolCalls(),
                input, output, unknown, answer, mapper.traces(run.getId()), run.getCreatedAt(), run.getDeadlineMs());
    }

    /** 仅为运行快照附加内存思考，历史消息接口不读取该缓存。 */
    private MessageView runtimeAnswer(Long spaceId, LoginUser user, Run run, MessageView answer) {
        AgentReasoningRegistry registry = reasoning.getIfAvailable();
        AgentReasoningRegistry.Entry entry = registry == null ? null : registry.get(run);

        if (entry == null || (answer != null && answer.masked())) return answer;

        boolean invalid;

        try {
            invalid = tools.currentDependencies(spaceId, user, entry.dependencies());
        } catch (AgentFailure e) {
            invalid = true;
        }

        if (invalid) {
            return new MessageView(answer == null ? null : answer.id(), run.getId(), "ASSISTANT",
                    "资料已更新或不可访问，思考和回答已隐藏。", true, List.of(), run.getCreatedAt(),
                    null, null, false, run.getStatus());
        }

        ReasoningProgress progress = entry.progress();

        return new MessageView(answer == null ? null : answer.id(), run.getId(), "ASSISTANT",
                answer == null ? "" : answer.text(), false, answer == null ? List.of() : answer.citations(),
                answer == null ? run.getCreatedAt() : answer.createdAt(), progress.content(), progress.durationMs(),
                progress.truncated(), run.getStatus());
    }

    private MessageView visible(Long spaceId, LoginUser user, Message message) {
        if (message == null) return null;

        if ("USER".equals(message.getRole()))
            return new MessageView(message.getId(), message.getRunId(), message.getRole(), message.getBody(), false,
                    List.of(), message.getCreatedAt(), null, null, false, message.getRunStatus());

        try {
            List<Dependency> dependencies = json.dependencies(message.getDependencies());

            if (tools.currentDependencies(spaceId, user, dependencies))
                return masked(message);

            List<Citation> sources = tools.citations(spaceId, user, json.sources(message.getSources()));

            if (tools.currentDependencies(spaceId, user, dependencies))
                return masked(message);

            return new MessageView(message.getId(), message.getRunId(), message.getRole(), message.getBody(), false,
                    sources, message.getCreatedAt(), null, null, false, message.getRunStatus());
        } catch (AgentFailure e) { return masked(message); }
    }

    private MessageView masked(Message message) {
        return new MessageView(message.getId(), message.getRunId(), message.getRole(), "资料已更新或不可访问，原回答已隐藏。", true,
                List.of(), message.getCreatedAt(), null, null, false, message.getRunStatus());
    }

    private Run ownedRun(Long spaceId, Long runId, LoginUser user) {
        Run run = mapper.run(runId);

        if (run == null || !spaceId.equals(run.getSpaceId()) || !user.getUserId().equals(run.getUserId()))
            throw new BusinessException("运行不存在");

        requireSession(spaceId, run.getSessionId(), user);

        if (("QUEUED".equals(run.getStatus()) || "RUNNING".equals(run.getStatus())) && run.getDeadlineMs() <= System.currentTimeMillis()) {
            if (mapper.endActive(runId, "TIMED_OUT", "RUN_TIMEOUT")) {
                worker.cancelModel(runId);
                events.publish(runId, "run_failed");
            }

            return mapper.run(runId);
        }

        return run;
    }

    private void requireSession(Long spaceId, Long sessionId, LoginUser user) {
        if (mapper.session(sessionId, spaceId, user.getUserId()) == null) throw new BusinessException("会话不存在");
    }

    private long offset(PageQuery page) {
        if (page.getCurrent() < 1 || page.getCurrent() > 10000 || page.getSize() < 1 || page.getSize() > 100)
            throw new BusinessException("分页参数超出范围");

        return (page.getCurrent() - 1) * page.getSize();
    }

    private <T> PageResult<T> page(List<T> records, long total, PageQuery page) {
        return new PageResult<>(records, total, page.getCurrent(), page.getSize(), (total + page.getSize() - 1) / page.getSize());
    }
}
