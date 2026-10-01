package asia.creat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "teamdocs.retrieval")
public class RetrievalProperties {
    /** 一次正文搜索最多返回的片段数 */
    private int searchLimit = 6;
    /** 一次顺序读取最多返回的片段数 */
    private int readLimit = 20;
    /** 一次返回的正文总字符上限 */
    private int maxChars = 4000;
    /** 可显式关闭；未配置可用客户端时仍走原关键词路径。 */
    private boolean hybridEnabled = true;
}
