package asia.creat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "teamdocs.agent")
public class AgentProperties {
    /**
     * AI 功能全局开关，默认关闭
     */
    private boolean enabled = false;

    /**
     * OpenAI 协议兼容接口 Base URL
     */
    private String baseUrl;

    /**
     * 访问 API 凭据
     */
    private String apiKey;

    /**
     * 模型标识名称
     */
    private String modelName;

    /**
     * 请求超时时间（秒），默认 60 秒
     */
    private int timeoutSeconds = 60;
}
