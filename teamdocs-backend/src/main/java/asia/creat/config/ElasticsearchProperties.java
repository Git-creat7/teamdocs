package asia.creat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "teamdocs.elasticsearch")
public class ElasticsearchProperties {
    private boolean enabled = false;
    private String url = "http://localhost:9200";
    private String index = "teamdocs-chunks";
    private int connectTimeoutMillis = 1000;
    private int connectionRequestTimeoutMillis = 500;
    private int socketTimeoutMillis = 2000;
}
