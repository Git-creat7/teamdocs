package asia.creat.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 本模块持久记录与对外 DTO；模型不能提供用户、空间或会话身份。 */
public final class AgentData {
    private AgentData() { }

    public record NewSession(@NotBlank @Size(max = 80) String title) { }
    public record NewRun(@NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String clientRequestId,
                         @NotBlank @Size(max = 2000) String question) { }

    @Data
    public static class Session {
        private Long id;
        private Long spaceId;
        private Long userId;
        private String title;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class Run {
        private Long id;
        private Long sessionId;
        private Long spaceId;
        private Long userId;
        private String clientRequestId;
        private String requestHash;
        private String modelName;
        private String status;
        private String errorCode;
        private int modelCalls;
        private int toolCalls;
        private int maxModelCalls;
        private int maxToolCalls;
        private int maxInputTokens;
        private int maxOutputTokens;
        private LocalDateTime createdAt;
        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;
        private Long deadlineMs;
        private String question;
    }

    @Data
    public static class Message {
        private Long id;
        private Long sessionId;
        private Long runId;
        private String role;
        private String body;
        private String dependencies;
        private String sources;
        private LocalDateTime createdAt;
    }

    @Data
    public static class Trace {
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
    public static class Charge {
        private Long id;
        private Long runId;
        private Long userId;
        private int sequence;
        private int estimatedInput;
        private int maxOutput;
        private BigDecimal inputPrice;
        private BigDecimal outputPrice;
        private BigDecimal reservedCost;
        private BigDecimal chargedCost;
        private Long inputTokens;
        private Long outputTokens;
        private boolean usageKnown;
    }

    public record Dependency(Long documentId, Integer parseVersion, String parseStatus,
                             String documentName, LocalDateTime updatedAt) { }
    public record Source(String id, Long documentId, Long chunkId, Integer parseVersion) { }
    public record Citation(String id, Long documentId, Long chunkId, Integer chunkIndex, Integer parseVersion, String documentName,
                           Integer pageNumber, Integer charStart, Integer charEnd, String excerpt, String url) { }
    public record MessageView(Long id, String role, String text, boolean masked,
                              List<Citation> citations, LocalDateTime createdAt) { }
    public record RunView(Long id, Long sessionId, String status, String errorCode, int modelCalls, int toolCalls,
                          long inputTokens, long outputTokens, BigDecimal chargedCost, boolean usageUnknown,
                          MessageView answer, List<Trace> tools, LocalDateTime createdAt, Long deadlineMs) { }
}
