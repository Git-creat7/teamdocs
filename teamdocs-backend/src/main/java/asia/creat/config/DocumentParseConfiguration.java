package asia.creat.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableConfigurationProperties({ParseProperties.class, RetrievalProperties.class})
@Slf4j
public class DocumentParseConfiguration {

    @Bean(name = "documentParseExecutor")
    public ThreadPoolTaskExecutor documentParseExecutor(ParseProperties properties) {
        int poolSize = Math.max(1, properties.getPoolSize());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(Math.max(1, properties.getBatchSize()));
        executor.setThreadNamePrefix("doc-parse-");
        executor.setRejectedExecutionHandler((runnable, pool) ->
                log.warn("解析队列已满，留待下次扫描"));
        executor.initialize();
        return executor;
    }
}
