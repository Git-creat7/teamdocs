package asia.creat.agent;

import asia.creat.agent.AgentData.*;
import asia.creat.mapper.AgentMapper;
import asia.creat.mapper.AgentSessionMapper;
import asia.creat.mapper.AgentMessageMapper;
import asia.creat.mapper.AgentTraceMapper;
import asia.creat.mapper.AgentModelCallMapper;
import asia.creat.mapper.UserMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.baomidou.mybatisplus.extension.toolkit.ChainWrappers.lambdaQueryChain;
import static com.baomidou.mybatisplus.extension.toolkit.ChainWrappers.lambdaUpdateChain;

/** 聚合问答相关表的持久化操作，事务仍由业务层管理。 */
@Repository
@RequiredArgsConstructor
public class AgentRepository extends ServiceImpl<AgentMapper, Run> {
    private final AgentSessionMapper sessions;
    private final AgentMessageMapper messages;
    private final AgentTraceMapper traces;
    private final AgentModelCallMapper modelCalls;
    private final UserMapper users;

    public Long lockUser(Long userId) {
        return users.lockActiveUser(userId);
    }

    public void insertSession(Session session) {
        sessions.insert(session);
    }

    public Session session(Long id, Long spaceId, Long userId) {
        return lambdaQueryChain(sessions)
                .eq(Session::getId, id).eq(Session::getSpaceId, spaceId).eq(Session::getUserId, userId).one();
    }

    public List<Session> sessions(Long spaceId, Long userId, long offset, long limit) {
        return lambdaQueryChain(sessions).eq(Session::getSpaceId, spaceId).eq(Session::getUserId, userId)
                .orderByDesc(Session::getId).page(new Page<>(offset / limit + 1, limit, false)).getRecords();
    }

    public long countSessions(Long spaceId, Long userId) {
        return lambdaQueryChain(sessions)
                .eq(Session::getSpaceId, spaceId).eq(Session::getUserId, userId).count();
    }

    public List<Run> sessionRuns(Long sessionId) {
        return lambdaQuery()
                .select(Run::getId, Run::getStatus).eq(Run::getSessionId, sessionId).list();
    }

    public void deleteSessionModelCalls(Long sessionId) {
        modelCalls.deleteSessionModelCalls(sessionId);
    }

    public void deleteSessionToolCalls(Long sessionId) {
        traces.deleteSessionToolCalls(sessionId);
    }

    public void deleteSessionMessages(Long sessionId) {
        lambdaUpdateChain(messages).eq(Message::getSessionId, sessionId).remove();
    }

    public void deleteSessionRuns(Long sessionId) {
        lambdaUpdate().eq(Run::getSessionId, sessionId).remove();
    }

    public void deleteSession(Long sessionId, Long spaceId, Long userId) {
        lambdaUpdateChain(sessions)
                .eq(Session::getId, sessionId).eq(Session::getSpaceId, spaceId).eq(Session::getUserId, userId).remove();
    }

    public Run existingRun(Long sessionId, String requestId) {
        return lambdaQuery()
                .eq(Run::getSessionId, sessionId).eq(Run::getClientRequestId, requestId).one();
    }

    public Run run(Long id) {
        return baseMapper.run(id);
    }

    public Run lockRun(Long id) {
        return baseMapper.lockRun(id);
    }

    public boolean hasActiveRuns(Long userId) {
        return lambdaQuery().eq(Run::getUserId, userId).in(Run::getStatus, "QUEUED", "RUNNING").exists();
    }


    public void insertMessage(Message message) {
        messages.insert(message);
    }

    public List<Message> messages(Long sessionId, long offset, long limit) {
        return messages.messages(sessionId, offset, limit);
    }

    public long countMessages(Long sessionId) {
        return lambdaQueryChain(messages).eq(Message::getSessionId, sessionId).count();
    }

    public Message answer(Long runId) {
        return messages.answer(runId);
    }

    public List<Message> history(Long sessionId, long beforeId, int limit) {
        return messages.history(sessionId, beforeId, limit);
    }


    public void touchSession(Long id) {
        lambdaUpdateChain(sessions).eq(Session::getId, id)
                .setSql("updated_at=CURRENT_TIMESTAMP(3)").update();
    }

    /**
     * 领取仍在排队且未超时的运行。
     * @param id 运行ID
     * @param nowMs 当前时间
     * @return 是否领取成功
     */
    public boolean claim(Long id, long nowMs) {
        return lambdaUpdate().eq(Run::getId, id)
                .eq(Run::getStatus, "QUEUED").gt(Run::getDeadlineMs, nowMs)
                .set(Run::getStatus, "RUNNING").setSql("started_at=CURRENT_TIMESTAMP(3)").update();
    }

    public boolean nextTool(Long id, long nowMs) {
        return lambdaUpdate().eq(Run::getId, id)
                .eq(Run::getStatus, "RUNNING").gt(Run::getDeadlineMs, nowMs)
                .ltSql(Run::getToolCalls, "max_tool_calls").setIncrBy(Run::getToolCalls, 1).update();
    }

    public boolean nextModel(Long id, long nowMs) {
        return lambdaUpdate().eq(Run::getId, id)
                .eq(Run::getStatus, "RUNNING").gt(Run::getDeadlineMs, nowMs)
                .ltSql(Run::getModelCalls, "max_model_calls").setIncrBy(Run::getModelCalls, 1).update();
    }

    public boolean finish(Long id, String status, String error, long nowMs) {
        return lambdaUpdate().eq(Run::getId, id)
                .eq(Run::getStatus, "RUNNING").gt(Run::getDeadlineMs, nowMs)
                .set(Run::getStatus, status).set(Run::getErrorCode, error)
                .setSql("finished_at=CURRENT_TIMESTAMP(3)").update();
    }

    public boolean endActive(Long id, String status, String error) {
        return lambdaUpdate().eq(Run::getId, id)
                .in(Run::getStatus, "QUEUED", "RUNNING")
                .set(Run::getStatus, status).set(Run::getErrorCode, error)
                .setSql("finished_at=CURRENT_TIMESTAMP(3)").update();
    }

    public boolean expire(long nowMs) {
        return lambdaUpdate()
                .in(Run::getStatus, "QUEUED", "RUNNING").le(Run::getDeadlineMs, nowMs)
                .set(Run::getStatus, "TIMED_OUT").set(Run::getErrorCode, "RUN_TIMEOUT")
                .setSql("finished_at=CURRENT_TIMESTAMP(3)").update();
    }

    public boolean recoverInterrupted() {
        return lambdaUpdate().in(Run::getStatus, "QUEUED", "RUNNING")
                .set(Run::getStatus, "FAILED").set(Run::getErrorCode, "PROCESS_INTERRUPTED")
                .setSql("finished_at=CURRENT_TIMESTAMP(3)").update();
    }

    public void insertTrace(Trace trace) {
        traces.insert(trace);
    }

    public void updateTrace(Trace trace) {
        lambdaUpdateChain(traces)
                .eq(Trace::getRunId, trace.getRunId()).eq(Trace::getSequence, trace.getSequence())
                .set(Trace::getResultSummary, trace.getResultSummary()).set(Trace::getStatus, trace.getStatus())
                .set(Trace::getErrorCode, trace.getErrorCode()).set(Trace::getDurationMs, trace.getDurationMs()).update();
    }

    public List<Trace> traces(Long runId) {
        return lambdaQueryChain(traces)
                .eq(Trace::getRunId, runId).orderByAsc(Trace::getSequence).last("LIMIT 8").list();
    }

    public void insertModelCall(ModelCall call) {
        modelCalls.insert(call);
    }

    /** 已记录用量的调用不再重复覆盖。 */
    public void recordModelUsage(ModelCall call) {
        lambdaUpdateChain(modelCalls)
                .eq(ModelCall::getId, call.getId()).eq(ModelCall::isUsageKnown, false)
                .set(ModelCall::getInputTokens, call.getInputTokens()).set(ModelCall::getOutputTokens, call.getOutputTokens())
                .set(ModelCall::isUsageKnown, true).update();
    }

    public List<ModelCall> modelCalls(Long runId) {
        return lambdaQueryChain(modelCalls)
                .eq(ModelCall::getRunId, runId).orderByAsc(ModelCall::getSequence).last("LIMIT 6").list();
    }
}
