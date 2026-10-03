package asia.creat.config;

import asia.creat.retrieval.RetrievalHttp;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "teamdocs.vision")
public class VisionProperties {
    private String baseUrl = "";
    @ToString.Exclude
    private String apiKey = "";
    private String modelName = "";
    private int timeoutSeconds = 20;
    private int maxImages = 8;
    private int maxImageBytes = 2097152;
    private long maxPixels = 16000000;
    private int maxDimension = 1600;
    private int maxOutputTokens = 4096;

    /** 检查视觉服务配置是否完整。 */
    public boolean isEnabled() {
        return RetrievalHttp.hasApiKey(apiKey) && baseUrl != null && !baseUrl.isBlank()
                && modelName != null && !modelName.isBlank();
    }
}
