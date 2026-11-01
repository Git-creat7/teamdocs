package asia.creat.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@EnableConfigurationProperties({EmbeddingProperties.class, RerankProperties.class, MilvusProperties.class, ElasticsearchProperties.class, RetrievalProperties.class})
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

    /**
     * 专用有界检索线程池，使用 AbortPolicy 保护，避免饱和时阻塞调用主线程。
     * @return 异步检索线程池
     */
    @Bean(name = "retrievalExecutor", destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "teamdocs.milvus", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ExecutorService retrievalExecutor() {
        return new ThreadPoolExecutor(
                2, 8,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(16),
                new ThreadFactory() {
                    private final AtomicInteger count = new AtomicInteger();
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "retrieval-async-" + count.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
