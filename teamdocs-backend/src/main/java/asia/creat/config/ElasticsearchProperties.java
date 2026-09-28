package asia.creat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "teamdocs.elasticsearch")
public class ElasticsearchProperties {
    private boolean enabled = false;
    private String url = "http://localhost:9200";
    private String index = "teamdocs-chunks";
}
