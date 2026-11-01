package asia.creat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "teamdocs.parse")
public class ParseProperties {
    /** 关闭后不扫描队列，上传和下载仍可用 */
    private boolean enabled = true;
    /** 摘要生成开关，需同时满足 enabled=true */
    private boolean summaryEnabled = true;
    private int summaryTimeoutSeconds = 60;
    private int summaryBatchSize = 5;
    private int summaryMaxChars = 3000;
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
