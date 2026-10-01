package asia.creat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "teamdocs.agent")
public class AgentProperties {
    /**
     * AI 功能开关，未配置时根据有效 Key 自动判断
     */
    private Boolean enabled;

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
    /** 可显式覆盖；未配置时，填写有效 Key 即确认问答资料出站。 */
    private Boolean allowDocumentEgress;
    private int maxModelCalls = 6;
    private int maxToolCalls = 8;
    private int runTimeoutSeconds = 90;
    private int maxInputTokens = 16000;
    private int maxOutputTokens = 1024;
    private int historyTurns = 3;

    /** 根据显式开关或有效 Key 判断是否启用问答。 */
    public boolean isEnabled() {
        return enabled != null ? enabled : asia.creat.retrieval.RetrievalHttp.hasApiKey(apiKey);
    }

    /** 有效 Key 的配置意味着允许问答资料出站，显式关闭优先。 */
    public boolean isAllowDocumentEgress() {
        return allowDocumentEgress != null ? allowDocumentEgress : isEnabled();
    }
}
