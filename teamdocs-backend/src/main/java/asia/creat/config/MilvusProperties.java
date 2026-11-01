package asia.creat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "teamdocs.milvus")
public class MilvusProperties {
    private boolean enabled = true;
    private String url = "http://127.0.0.1:19530";
    private String token;
    private String collection = "teamdocs_qwen3_8b_d1024_v1";
    private int timeoutSeconds = 3;
    private int connectTimeoutMillis = 1500;
    private int readTimeoutMillis = 3000;
    private int writeTimeoutMillis = 3000;
    private String indexType = "FLAT";
    private int hnswM = 16;
    private int hnswEfConstruction = 200;
    private int hnswEf = 64;

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
        this.readTimeoutMillis = timeoutSeconds * 1000;
    }
}
