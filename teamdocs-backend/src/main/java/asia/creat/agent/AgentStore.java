package asia.creat.agent;

import asia.creat.agent.AgentData.*;
import asia.creat.common.exception.BusinessException;
import asia.creat.config.AgentProperties;
import asia.creat.memory.UserMemoryService;
import asia.creat.model.UserModelService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AgentStore {
    private final AgentRepository mapper;
    private final AgentProperties properties;
    private final AgentJson json;
    private final AgentEventHub events;
    private final ObjectProvider<UserMemoryService> memory;

    @Nullable
    private final UserModelService personalModels;

    @Nullable
    private final AgentScopeService scopes;

    @Nullable
    private final ChatAttachmentService attachments;

    public record Created(Run run, boolean created) { }

    @Transactional
    public Session createSession(Long spaceId, Long userId, String title) {
        lockUser(userId);

        Session session = new Session();
        session.setSpaceId(spaceId);
        session.setUserId(userId);
        session.setTitle(title.trim());

        mapper.insertSession(session);

        return mapper.session(session.getId(), spaceId, userId);
    }

    @Transactional
    public Created createRun(Long spaceId, Long sessionId, Long userId, NewRun request) {
        lockUser(userId);

        if (mapper.session(sessionId, spaceId, userId) == null) throw new BusinessException("会话不存在");

        String question = request.question().trim();
        if (request.documentIds() != null && (request.documentId() != null || request.folderId() != null)) {
            throw new BusinessException("文件列表不能与单文件或文件夹范围同时提交");
        }
        List<Long> requestedIds = request.documentIds() == null ? null : AgentScopeService.normalize(request.documentIds());
        String hash = hash(requestedIds != null ? question + "\ndocuments:" + json.write(requestedIds)
                : request.documentId() == null && request.folderId() == null
                ? question : question + "\n" + request.documentId() + ":" + request.folderId());
        if (request.attachmentIds() != null && !request.attachmentIds().isEmpty()) {
            hash = hash(hash + "\nattachments:" + json.write(request.attachmentIds()));
        }
        Run existing = mapper.existingRun(sessionId, request.clientRequestId());

        if (existing != null) {
            if (!hash.equals(existing.getRequestHash())) throw new BusinessException("同一请求标识不能提交不同问题");

            return new Created(existing, false);
        }

        List<Long> scopeIds = null;
        if (request.documentId() != null || request.folderId() != null || requestedIds != null) {
            if (scopes == null) throw new BusinessException("范围服务不可用");
            scopeIds = scopes.resolve(spaceId, request.documentId(), request.folderId(), requestedIds);
        }
        if (personalModels == null) AgentBudget.requireConfigured(properties);

        if (mapper.hasActiveRuns(userId)) throw new BusinessException("当前用户已有运行中的 AI 请求");

        Run run = Run.builder()
                .spaceId(spaceId)
                .userId(userId)
                .sessionId(sessionId)
                .clientRequestId(request.clientRequestId())
                .requestHash(hash)
                .scopeDocumentId(request.documentId())
                .scopeFolderId(request.folderId())
                .scopeDocumentIds(scopeIds == null ? null : json.write(scopeIds))
                .modelName(properties.getModelName())
                .maxModelCalls(Math.min(6, Math.max(1, properties.getMaxModelCalls())))
                .maxToolCalls(Math.min(8, Math.max(1, properties.getMaxToolCalls())))
                .maxOutputTokens(Math.max(0, properties.getMaxOutputTokens()))
                .deadlineMs(System.currentTimeMillis() + Math.min(90, Math.max(1, properties.getRunTimeoutSeconds())) * 1000L)
                .build();

        if (personalModels != null) personalModels.snapshot(run);
        run.setMaxInputTokens(AgentBudget.inputLimit(properties, run.getModelName()));
        mapper.save(run);
        if (request.attachmentIds() != null && !request.attachmentIds().isEmpty()) {
            if (attachments == null) throw new BusinessException("附件服务未启用");
            attachments.bind(run, request.attachmentIds());
        }
        memory.ifAvailable(service -> service.register(run));

        Message message = Message.builder()
                .sessionId(sessionId)
                .runId(run.getId())
                .role("USER")
                .body(question)
                .dependencies("[]")
                .sources("[]")
                .build();

        mapper.insertMessage(message);
        mapper.touchSession(sessionId);

        return new Created(mapper.run(run.getId()), true);
    }

    @Transactional
    public boolean finish(Run run, String status, String error, String answer, List<Dependency> dependencies, List<Source> sources) {
        if (!mapper.finish(run.getId(), status, error, System.currentTimeMillis())) return false;

        Message message = Message.builder()
                .sessionId(run.getSessionId())
                .runId(run.getId())
                .role("ASSISTANT")
                .body(answer)
                .dependencies(json.write(dependencies))
                .sources(json.write(sources))
                .build();

        mapper.insertMessage(message);
        mapper.touchSession(run.getSessionId());

        events.afterCommit(run.getId(), "SUCCEEDED".equals(status) ? "run_finished" : "run_failed");

        return true;
    }

    private void lockUser(Long userId) {
        if (mapper.lockUser(userId) == null) throw new BusinessException("用户不可用");
    }

    private String hash(String question) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(question.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
