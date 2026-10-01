package asia.creat.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableConfigurationProperties({EmbeddingProperties.class, RerankProperties.class, MilvusProperties.class})
public class SemanticRetrievalConfiguration {
    /**
     * 使用独立单线程调度器，避免外部向量调用阻塞解析扫描。
     * @return 向量任务调度器
     */
    @Bean
    @ConditionalOnProperty(prefix = "teamdocs.milvus", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ThreadPoolTaskScheduler vectorTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("vector-index-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
