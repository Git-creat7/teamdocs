package asia.creat.agent;

import asia.creat.agent.AgentData.*;
import asia.creat.common.exception.BusinessException;
import asia.creat.config.AgentProperties;
import asia.creat.mapper.AgentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentStore {
    private final AgentMapper mapper;
    private final AgentProperties properties;
    private final AgentJson json;
    private final AgentEventHub events;

    public record Created(Run run, boolean created) { }

    @Transactional
    public Session createSession(Long spaceId, Long userId, String title) {
        lockUser(userId);
        Session session = new Session();
        session.setSpaceId(spaceId); session.setUserId(userId); session.setTitle(title.trim());
        mapper.insertSession(session);
        return mapper.session(session.getId(), spaceId, userId);
    }

    @Transactional
    public Created createRun(Long spaceId, Long sessionId, Long userId, NewRun request) {
        lockUser(userId);
        if (mapper.session(sessionId, spaceId, userId) == null) throw new BusinessException("会话不存在");
        String question = request.question().trim();
        String hash = hash(question);
        Run existing = mapper.existingRun(sessionId, request.clientRequestId());
        if (existing != null) {
            if (!hash.equals(existing.getRequestHash())) throw new BusinessException("同一请求标识不能提交不同问题");
            return new Created(existing, false);
        }
        AgentBudget.requireConfigured(properties);
        if (mapper.activeRuns(userId) != 0) throw new BusinessException("当前用户已有运行中的 AI 请求");
        Run run = Run.builder()
                .spaceId(spaceId)
                .userId(userId)
                .sessionId(sessionId)
                .clientRequestId(request.clientRequestId())
                .requestHash(hash)
                .modelName(properties.getModelName())
                .maxModelCalls(Math.min(6, Math.max(1, properties.getMaxModelCalls())))
                .maxToolCalls(Math.min(8, Math.max(1, properties.getMaxToolCalls())))
                .maxInputTokens(Math.max(1, properties.getMaxInputTokens()))
                .maxOutputTokens(Math.min(4096, Math.max(1, properties.getMaxOutputTokens())))
                .deadlineMs(System.currentTimeMillis() + Math.min(90, Math.max(1, properties.getRunTimeoutSeconds())) * 1000L)
                .build();
        mapper.insertRun(run);
        Message message = Message.builder()
                .sessionId(sessionId)
                .runId(run.getId())
                .role("USER")
                .body(question)
                .dependencies("[]")
                .sources("[]")
                .build();
        mapper.insertMessage(message); mapper.touchSession(sessionId);
        return new Created(mapper.run(run.getId()), true);
    }

    @Transactional
    public boolean finish(Run run, String status, String error, String answer, List<Dependency> dependencies, List<Source> sources) {
        if (mapper.finish(run.getId(), status, error, System.currentTimeMillis()) != 1) return false;
        Message message = Message.builder()
                .sessionId(run.getSessionId())
                .runId(run.getId())
                .role("ASSISTANT")
                .body(answer)
                .dependencies(json.write(dependencies))
                .sources(json.write(sources))
                .build();
        mapper.insertMessage(message); mapper.touchSession(run.getSessionId());
        events.afterCommit(run.getId(), "SUCCEEDED".equals(status) ? "run_finished" : "run_failed");
        return true;
    }

    private void lockUser(Long userId) {
        if (mapper.lockUser(userId) == null) throw new BusinessException("用户不可用");
    }

    private String hash(String question) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(question.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
