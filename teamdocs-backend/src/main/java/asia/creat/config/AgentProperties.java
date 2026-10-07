package asia.creat.config;

import asia.creat.retrieval.RetrievalHttp;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "teamdocs.agent")
public class AgentProperties {
    /**
     * AI 功能开关，未配置时根据有效 Key 自动判断
     */
    private Boolean enabled;
    /** 仅由服务端为用户自定义地址设置，系统内网依赖不受此限制。 */
    private boolean publicEndpointOnly;
    /** 管理员配置的可信 HTTP 出站代理，空值遵循 JVM 默认 ProxySelector。 */
    private String proxyUrl;

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
     * 连接及读写空闲超时；非流式请求也用作单次总超时，默认60秒
     */
    private int timeoutSeconds = 60;
    /** 请求流式响应；不支持流的兼容接口可显式关闭，不自动重放。 */
    private boolean streaming = true;
    /** 可显式覆盖；未配置时，填写有效 Key 即确认问答资料出站。 */
    private Boolean allowDocumentEgress;
    private int maxModelCalls = 6;
    private int maxToolCalls = 8;
    private int runTimeoutSeconds = 90;
    /** 可用输入容量覆盖值；0 按已知模型窗口扣除生成空间及估算余量。 */
    private int maxInputTokens = 0;
    /** 非正数表示不主动指定输出 Token 上限；检测和记忆抽取可显式设置小预算。 */
    private int maxOutputTokens = 0;

    /** 根据显式开关或有效 Key 判断是否启用问答。 */
    public boolean isEnabled() {
        return enabled != null ? enabled : RetrievalHttp.hasApiKey(apiKey);
    }

    /** 有效 Key 的配置意味着允许问答资料出站，显式关闭优先。 */
    public boolean isAllowDocumentEgress() {
        return allowDocumentEgress != null ? allowDocumentEgress : isEnabled();
    }
}
