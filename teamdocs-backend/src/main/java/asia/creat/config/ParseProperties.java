package asia.creat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "teamdocs.parse")
public class ParseProperties {
    /** 关闭后不扫描队列，上传和下载仍可用 */
    private boolean enabled = true;
    private long maxBytes = 10L * 1024 * 1024;
    private int maxChars = 200_000;
    private int timeoutSeconds = 120;
    private int retryDelaySeconds = 5;
    private int chunkSize = 1000;
    private int chunkOverlap = 120;
    private int batchSize = 5;
    private int poolSize = 2;
    private long scanDelayMs = 5000;
}
