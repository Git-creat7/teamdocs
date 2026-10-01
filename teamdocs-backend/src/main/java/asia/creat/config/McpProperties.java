package asia.creat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "teamdocs.agent.mcp")
public class McpProperties {
    private boolean enabled = true;
    private Map<String, ServerConfig> servers = new HashMap<>();

    public void setEnable(boolean enable) {
        this.enabled = enable;
    }

    public boolean isEnable() {
        return enabled;
    }

    @Data
    public static class ServerConfig {
        private String type = "http";
        private String url;
        private Map<String, String> headers = new HashMap<>();
    }
}