package asia.creat.mapper;

import asia.creat.agent.AgentData.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AgentMapper {
    Long lockUser(@Param("userId") Long userId);

    int insertSession(Session session);

    Session session(@Param("id") Long id, @Param("spaceId") Long spaceId, @Param("userId") Long userId);

    List<Session> sessions(@Param("spaceId") Long spaceId, @Param("userId") Long userId, @Param("offset") long offset, @Param("limit") long limit);

    long countSessions(@Param("spaceId") Long spaceId, @Param("userId") Long userId);
    List<Run> sessionRuns(@Param("sessionId") Long sessionId);
    int deleteSessionModelCalls(@Param("sessionId") Long sessionId);
    int deleteSessionToolCalls(@Param("sessionId") Long sessionId);
    int deleteSessionMessages(@Param("sessionId") Long sessionId);
    int deleteSessionRuns(@Param("sessionId") Long sessionId);
    int deleteSession(@Param("sessionId") Long sessionId, @Param("spaceId") Long spaceId, @Param("userId") Long userId);


    Run existingRun(@Param("sessionId") Long sessionId, @Param("requestId") String requestId);

    Run run(@Param("id") Long id);

    Run lockRun(@Param("id") Long id);

    int activeRuns(@Param("userId") Long userId);

    int insertRun(Run run);

    int insertMessage(Message message);

    List<Message> messages(@Param("sessionId") Long sessionId, @Param("offset") long offset, @Param("limit") long limit);

    long countMessages(@Param("sessionId") Long sessionId);

    Message answer(@Param("runId") Long runId);

    List<Message> history(@Param("sessionId") Long sessionId, @Param("limit") int limit);

    int touchSession(@Param("id") Long id);

    int claim(@Param("id") Long id, @Param("nowMs") long nowMs);

    int nextTool(@Param("id") Long id, @Param("nowMs") long nowMs);

    int nextModel(@Param("id") Long id, @Param("nowMs") long nowMs);

    int finish(@Param("id") Long id, @Param("status") String status, @Param("error") String error, @Param("nowMs") long nowMs);

    int endActive(@Param("id") Long id, @Param("status") String status, @Param("error") String error);

    int expire(@Param("nowMs") long nowMs);

    int recoverInterrupted();

    int insertTrace(Trace trace);

    int updateTrace(Trace trace);

    List<Trace> traces(@Param("runId") Long runId);

    int insertModelCall(ModelCall call);

    int recordModelUsage(ModelCall call);

    List<ModelCall> modelCalls(@Param("runId") Long runId);
}
