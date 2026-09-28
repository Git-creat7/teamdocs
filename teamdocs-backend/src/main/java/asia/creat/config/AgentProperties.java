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
    /** 仅在管理员确认文档出站范围后开启；P0 的连通性开关不等于授权读取真实空间。 */
    private boolean allowDocumentEgress = false;
    private int maxModelCalls = 6;
    private int maxToolCalls = 8;
    private int runTimeoutSeconds = 90;
    private int maxInputTokens = 16000;
    private int maxOutputTokens = 1024;
    private int historyTurns = 3;
    /** 同一币种的每百万 token 报价和每用户日额度；缺失时拒绝新的运行。 */
    private java.math.BigDecimal inputPricePerMillion;
    private java.math.BigDecimal outputPricePerMillion;
    private java.math.BigDecimal dailyBudget;
}
