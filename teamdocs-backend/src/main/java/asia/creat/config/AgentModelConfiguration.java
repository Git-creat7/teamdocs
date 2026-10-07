package asia.creat.config;

import asia.creat.agent.model.OpenAiReasoningChatModel;
import asia.creat.retrieval.RetrievalHttp;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * 仅负责读取配置并在开启时条件装配低层 ChatLanguageModel。
 * 工具循环由 AgentWorker 显式控制，不使用 AI Services 自动执行工具。
 */
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
@Slf4j
@RequiredArgsConstructor
public class AgentModelConfiguration {

    private final AgentProperties properties;

    /** 根据显式配置构建聊天模型，不使用默认或兜底供应商。 */
    @Bean
    @Conditional(ConfiguredModel.class)
    public ChatLanguageModel chatLanguageModel() {
        if (!properties.isEnabled() || !RetrievalHttp.hasApiKey(properties.getApiKey())
                || !StringUtils.hasText(properties.getModelName()) || !StringUtils.hasText(properties.getBaseUrl())) {
            log.warn("teamdocs.agent.enabled=true，但未配置 apiKey 或 modelName，不创建外部模型 Bean");

            return null;
        }

        log.info("成功创建流式 ChatLanguageModel 实例: modelName={}", properties.getModelName());

        return new OpenAiReasoningChatModel(properties, new ObjectMapper());
    }

    static class ConfiguredModel implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            AgentProperties configured = Binder.get(context.getEnvironment())
                    .bind("teamdocs.agent", AgentProperties.class).orElseGet(AgentProperties::new);

            return configured.isEnabled() && RetrievalHttp.hasApiKey(configured.getApiKey())
                    && StringUtils.hasText(configured.getModelName()) && StringUtils.hasText(configured.getBaseUrl());
        }
    }
}
