package asia.creat.agent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class AgentExecutionConfiguration {
    @Bean(name = "agentWorkerExecutor", destroyMethod = "shutdownNow")
    public ExecutorService workers() { return pool("agent-run-", new ArrayBlockingQueue<>(8)); }

    @Bean(name = "agentModelExecutor", destroyMethod = "shutdownNow")
    public ExecutorService models() { return pool("agent-model-", new SynchronousQueue<>()); }

    @Bean(name = "agentEventExecutor", destroyMethod = "shutdownNow")
    public ExecutorService eventsExecutor() { return pool("agent-events-", new ArrayBlockingQueue<>(32)); }

    @Bean
    public AgentEventHub agentEventHub(@org.springframework.beans.factory.annotation.Qualifier("agentEventExecutor") ExecutorService executor) {
        return new AgentEventHub(executor);
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
