package asia.creat.agent;

import asia.creat.agent.AgentData.*;
import asia.creat.anno.RequireSpaceRole;
import asia.creat.anno.SpaceId;
import asia.creat.common.PageResult;
import asia.creat.common.exception.BusinessException;
import asia.creat.dto.PageQuery;
import asia.creat.mapper.AgentMapper;
import asia.creat.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentService {
    private final AgentMapper mapper;
    private final AgentStore store;
    private final AgentWorker worker;
    private final AgentTools tools;
    private final AgentJson json;

    @RequireSpaceRole
    public Session createSession(@SpaceId Long spaceId, NewSession request, LoginUser user) {
        return store.createSession(spaceId, user.getUserId(), request.title());
    }

    @RequireSpaceRole
    public PageResult<Session> sessions(@SpaceId Long spaceId, PageQuery page, LoginUser user) {
        long offset = offset(page);
        long total = mapper.countSessions(spaceId, user.getUserId());
        return page(mapper.sessions(spaceId, user.getUserId(), offset, page.getSize()), total, page);
    }

    @RequireSpaceRole
    public Long submit(@SpaceId Long spaceId, Long sessionId, NewRun request, LoginUser user) {
        requireSession(spaceId, sessionId, user);
        if (mapper.existingRun(sessionId, request.clientRequestId()) == null && !worker.available())
            throw new BusinessException("AI 执行服务尚未就绪或模型未配置");
        try {
            AgentStore.Created created = store.createRun(spaceId, sessionId, user.getUserId(), request);
            if (created.created()) worker.enqueue(created.run().getId(), user);
            return created.run().getId();
        } catch (AgentFailure e) { throw new BusinessException(e.code()); }
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
        ownedRun(spaceId, runId, user);
        mapper.endActive(runId, "CANCELLED", "USER_CANCELLED");
        return snapshot(spaceId, user, mapper.run(runId));
    }

    private RunView snapshot(Long spaceId, LoginUser user, Run run) {
        long input = 0, output = 0;
        BigDecimal charged = BigDecimal.ZERO;
        boolean unknown = false;
        for (Charge charge : mapper.charges(run.getId())) {
            input += charge.getInputTokens() == null ? 0 : charge.getInputTokens();
            output += charge.getOutputTokens() == null ? 0 : charge.getOutputTokens();
            charged = charged.add(charge.getChargedCost());
            unknown |= !charge.isUsageKnown();
        }
        return new RunView(run.getId(), run.getSessionId(), run.getStatus(), run.getErrorCode(), run.getModelCalls(), run.getToolCalls(),
                input, output, charged, unknown, visible(spaceId, user, mapper.answer(run.getId())), mapper.traces(run.getId()),
                run.getCreatedAt(), run.getDeadlineMs());
    }

    private MessageView visible(Long spaceId, LoginUser user, Message message) {
        if (message == null) return null;
        if ("USER".equals(message.getRole()))
            return new MessageView(message.getId(), message.getRole(), message.getBody(), false, List.of(), message.getCreatedAt());
        try {
            List<Dependency> dependencies = json.dependencies(message.getDependencies());
            if (!tools.currentDependencies(spaceId, user, dependencies)) return masked(message);
            List<Citation> sources = tools.citations(spaceId, user, json.sources(message.getSources()));
            if (!tools.currentDependencies(spaceId, user, dependencies)) return masked(message);
            return new MessageView(message.getId(), message.getRole(), message.getBody(), false, sources, message.getCreatedAt());
        } catch (AgentFailure e) { return masked(message); }
    }

    private MessageView masked(Message message) {
        return new MessageView(message.getId(), message.getRole(), "资料已更新或不可访问，原回答已隐藏。", true, List.of(), message.getCreatedAt());
    }
    private Run ownedRun(Long spaceId, Long runId, LoginUser user) {
        Run run = mapper.run(runId);
        if (run == null || !spaceId.equals(run.getSpaceId()) || !user.getUserId().equals(run.getUserId()))
            throw new BusinessException("运行不存在");
        requireSession(spaceId, run.getSessionId(), user);
        if (("QUEUED".equals(run.getStatus()) || "RUNNING".equals(run.getStatus())) && run.getDeadlineMs() <= System.currentTimeMillis()) {
            mapper.endActive(runId, "TIMED_OUT", "RUN_TIMEOUT");
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
