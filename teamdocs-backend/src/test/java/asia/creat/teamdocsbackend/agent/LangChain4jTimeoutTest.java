package asia.creat.teamdocsbackend.agent;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LangChain4jTimeoutTest {
    @Test
    void slowEndpointTimesOutWithoutHiddenRetries() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();

            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .apiKey("local-test-key")
                    .modelName("timeout-test")
                    .timeout(Duration.ofMillis(250))
                    .maxRetries(0)
                    .build();

            assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    assertThrows(RuntimeException.class, () -> model.generate("timeout sample")));
            assertEquals(1, requests.get());
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
