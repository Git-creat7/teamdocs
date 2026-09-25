package asia.creat.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.net.URI;

/**
 * AI 模型接入配置类（P0 阶段）
 * 仅负责读取配置并在开启时条件装配 ChatLanguageModel。
 * 严禁提前引入业务 Agent 循环、ProviderFactory 等 P3 抽象。
 */
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
@Slf4j
@RequiredArgsConstructor
public class AgentModelConfiguration {

    private final AgentProperties properties;

    @Bean
    @ConditionalOnProperty(prefix = "teamdocs.agent", name = "enabled", havingValue = "true")
    public ChatLanguageModel chatLanguageModel() {
        if (!StringUtils.hasText(properties.getApiKey()) || !StringUtils.hasText(properties.getModelName())) {
            log.warn("teamdocs.agent.enabled=true，但未配置 apiKey 或 modelName，不创建外部模型 Bean");
            return null;
        }

        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .apiKey(properties.getApiKey())
                .modelName(properties.getModelName())
                .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                .maxRetries(0)       // 强制关闭 SDK 内部不可控重试
                .logRequests(false)  // 关闭请求体正文与敏感凭据日志
                .logResponses(false); // 关闭响应体日志

        if (StringUtils.hasText(properties.getBaseUrl())) {
            String baseUrl = properties.getBaseUrl().trim();
            String path = URI.create(baseUrl).getPath();
            // 只为站点根地址补标准路径，显式配置的网关路径保持原样。
            if (path == null || path.isEmpty() || "/".equals(path)) {
                baseUrl = baseUrl.replaceAll("/+$", "") + "/v1";
            }
            builder.baseUrl(baseUrl);
        }

        log.info("成功创建通用 ChatLanguageModel 实例: modelName={}", properties.getModelName());
        return builder.build();
    }
}
