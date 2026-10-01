package asia.creat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "teamdocs.embedding")
public class EmbeddingProperties {
    private Boolean enabled;
    /** 可显式覆盖；未配置时，填写有效 Key 即确认该功能出站。 */
    private Boolean allowDocumentEgress;
    private String baseUrl = "";
    private String apiKey = "";
    private String modelName = "";
    private int dimensions = 1024;
    private int timeoutSeconds = 10;

    /** 根据显式开关或有效 Key 判断是否启用。 */
    public boolean isEnabled() {
        return enabled != null ? enabled : asia.creat.retrieval.RetrievalHttp.hasApiKey(apiKey);
    }

    /** 有效 Key 的配置意味着允许该功能发送资料，显式关闭优先。 */
    public boolean isAllowDocumentEgress() {
        return allowDocumentEgress != null ? allowDocumentEgress : isEnabled();
    }
}
