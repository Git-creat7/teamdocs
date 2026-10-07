package asia.creat.agent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** 本模块持久记录与对外 DTO；模型不能提供用户、空间或会话身份。 */
public final class AgentData {
    private AgentData() { }

    public record NewSession(@NotBlank @Size(max = 80) String title) { }

    public record NewRun(@NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String clientRequestId,
                         @NotBlank @Size(max = 2000) String question,
                         @Positive Long documentId,
                         @Positive Long folderId,
                         @Size(min = 1, max = 30) List<@NotNull @Positive Long> documentIds,
                         @Size(max = 4) List<@NotBlank @Size(max = 36) String> attachmentIds) {
        public NewRun(String clientRequestId, String question) { this(clientRequestId, question, null, null, null, null); }
        public NewRun(String clientRequestId, String question, Long documentId, Long folderId, List<Long> documentIds) {
            this(clientRequestId, question, documentId, folderId, documentIds, null);
        }
        public NewRun(String clientRequestId, String question, Long documentId, Long folderId) {
            this(clientRequestId, question, documentId, folderId, null, null);
        }
    }

    @Data
    @TableName("agent_session")
    public static class Session {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long spaceId;
        private Long userId;
        private String title;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @TableName("agent_run")
    public static class Run {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long sessionId;
        private Long spaceId;
        private Long userId;
        private String clientRequestId;
        private String requestHash;
        private Long scopeDocumentId;
        private Long scopeFolderId;
        private String scopeDocumentIds;
        private String modelName;
        @ToString.Exclude
        private String modelConfigCiphertext;
        private String status;
        private String errorCode;
        private int modelCalls;
        private int toolCalls;
        private int maxModelCalls;
        private int maxToolCalls;
        private int maxInputTokens;
        /** 0 表示本次不指定输出 Token 上限。 */
        private int maxOutputTokens;
        private LocalDateTime createdAt;
        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;
        private Long deadlineMs;
        @TableField(exist = false)
        private String question;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @TableName("agent_message")
    public static class Message {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long sessionId;
        private Long runId;
        private String role;
        private String body;
        private String dependencies;
        private String sources;
        @TableField(exist = false)
        private String runStatus;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @TableName("agent_tool_call")
    public static class Trace {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long runId;
        private int sequence;
        private String toolName;
        private String argumentSummary;
        private String resultSummary;
        private String status;
        private String errorCode;
        private long durationMs;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @TableName("agent_model_call")
    public static class ModelCall {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long runId;
        private Long userId;
        private int sequence;
        private int estimatedInput;
        /** 0 表示未指定输出上限，仍记录实际用量。 */
        private int maxOutput;
        private Long inputTokens;
        private Long outputTokens;
        private boolean usageKnown;
    }

    public record Dependency(Long documentId, Integer parseVersion, String parseStatus,
                             String documentName, LocalDateTime updatedAt) { }

    public record Source(String id, Long documentId, Long chunkId, Integer parseVersion) { }

    public record Citation(String id, Long documentId, Long chunkId, Integer chunkIndex, Integer parseVersion, String documentName,
                           Integer pageNumber, Integer charStart, Integer charEnd, String excerpt, String url,
                           boolean imageSource, String imageLabel) {
        /** 兼容普通文本来源，不要求旧调用提供图片信息。 */
        public Citation(String id, Long documentId, Long chunkId, Integer chunkIndex, Integer parseVersion, String documentName,
                        Integer pageNumber, Integer charStart, Integer charEnd, String excerpt, String url) {
            this(id, documentId, chunkId, chunkIndex, parseVersion, documentName, pageNumber, charStart, charEnd,
                    excerpt, url, false, null);
        }
    }

    public record ReasoningProgress(String content, Long durationMs, boolean truncated) { }

    public record MessageView(Long id, Long runId, String role, String text, boolean masked,
                              List<Citation> citations, LocalDateTime createdAt, String reasoningContent,
                              Long reasoningDurationMs, boolean reasoningTruncated, String runStatus) {
        /** 兼容无思考字段的消息与旧测试夹具。 */
        public MessageView(Long id, Long runId, String role, String text, boolean masked,
                           List<Citation> citations, LocalDateTime createdAt) {
            this(id, runId, role, text, masked, citations, createdAt, null, null, false, null);
        }
    }

    public record RunView(Long id, Long sessionId, String status, String errorCode, int modelCalls, int toolCalls,
                          long inputTokens, long outputTokens, boolean usageUnknown,
                          MessageView answer, List<Trace> tools, LocalDateTime createdAt, Long deadlineMs,
                          String reasoningContent, Long reasoningDurationMs, boolean reasoningTruncated) {
        /** 思考进度从已完成可见性检查的同一消息投影，不另存一份文本。 */
        public RunView(Long id, Long sessionId, String status, String errorCode, int modelCalls, int toolCalls,
                       long inputTokens, long outputTokens, boolean usageUnknown,
                       MessageView answer, List<Trace> tools, LocalDateTime createdAt, Long deadlineMs) {
            this(id, sessionId, status, errorCode, modelCalls, toolCalls, inputTokens, outputTokens, usageUnknown,
                    answer, tools, createdAt, deadlineMs,
                    answer == null || answer.masked() ? null : answer.reasoningContent(),
                    answer == null || answer.masked() ? null : answer.reasoningDurationMs(),
                    answer != null && !answer.masked() && answer.reasoningTruncated());
        }
    }
}
