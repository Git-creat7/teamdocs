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
}
