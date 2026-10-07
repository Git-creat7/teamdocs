package asia.creat.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.ToString;

public final class UserModelData {
    private UserModelData() { }

    @Data
    @TableName("user_model_config")
    public static class Config {
        @TableId(type = IdType.INPUT)
        private Long userId;
        private boolean enabled;
        private long version;
        private String baseUrl;
        private String modelName;
        @ToString.Exclude
        private String keyCiphertext;
    }

    // 不生成包含密钥的 toString，防止校验和请求日志暴露认证信息。
    public record Edit(@NotNull Boolean enabled, @NotNull @Min(0) Long version,
                       @NotBlank @Size(max = 500) String baseUrl,
                       @NotBlank @Size(max = 100) String modelName,
                       @Size(max = 4096) String apiKey) {
        @Override public String toString() { return "UserModelEdit[redacted]"; }
    }

    public record Version(@NotNull @Min(0) Long version) { }

    public record View(boolean enabled, long version, String baseUrl, String modelName,
                       boolean hasKey, boolean encryptionReady) { }

    public record Credentials(String baseUrl, String modelName, String apiKey) {
        @Override public String toString() { return "ModelCredentials[redacted]"; }
    }
}
