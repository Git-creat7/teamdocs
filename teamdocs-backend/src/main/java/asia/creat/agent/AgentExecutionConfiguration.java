package asia.creat.agent;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class AgentExecutionConfiguration {
    @Bean(name = "agentWorkerExecutor", destroyMethod = "shutdownNow")
    public ExecutorService workers() { return pool("agent-run-", new ArrayBlockingQueue<>(8)); }

    @Bean(name = "agentModelExecutor", destroyMethod = "shutdownNow")
    public ExecutorService models() { return pool("agent-model-", new SynchronousQueue<>()); }

    @Bean(name = "agentEventExecutor", destroyMethod = "shutdownNow")
    public ExecutorService eventsExecutor() { return pool("agent-events-", new ArrayBlockingQueue<>(32)); }

    @Bean
    public AgentEventHub agentEventHub(@Qualifier("agentEventExecutor") ExecutorService executor) {
        return new AgentEventHub(executor);
    }

    /** 独立单线程处理持久化记忆任务，不占用问答或向量同步线程。 */
    @Bean
    public ThreadPoolTaskScheduler userMemoryTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("user-memory-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    private ExecutorService pool(String prefix, BlockingQueue<Runnable> queue) {
        AtomicInteger number = new AtomicInteger();

        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, queue, task -> {
            Thread thread = new Thread(task, prefix + number.incrementAndGet());
            thread.setDaemon(true);

            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
}
