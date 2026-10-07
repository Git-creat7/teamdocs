package asia.creat.memory;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import java.time.LocalDateTime;

import java.util.List;

public final class UserMemoryData {
    private UserMemoryData() { }

    public record Item(String key, String value, Long sourceRunId, String updatedAt) { }

    public record Candidate(String key, String value, String evidence) { }

    public record Extraction(List<Candidate> candidates) { }

    public record View(boolean enabled, long version, List<Item> items) { }

    public record Settings(@NotNull Boolean enabled, @NotNull @Min(0) Long version) { }

    public record Edit(@NotBlank @Size(max = 160) String content, @NotNull @Min(0) Long version) { }

    @Data
    @TableName("user_memory")
    public static class Row {
        @TableId(type = IdType.INPUT)
        private Long userId;
        private boolean enabled;
        // 用户操作的代次；自动更新不推进它，避免相邻对话互相作废。
        private long version;
        private String itemsJson;
    }

    @Data
    @TableName("user_memory_job")
    public static class Job {
        @TableId(type = IdType.INPUT)
        private Long runId;
        private Long userId;
        private long memoryVersion;
        private int attempts;
        private String status;
        private long nextAttemptMs;
        private long leaseUntilMs;
        private LocalDateTime createdAt;
        private String claimToken;
        @TableField(exist = false)
        private String question;
    }
}
