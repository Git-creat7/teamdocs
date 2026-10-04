package asia.creat.parse;

import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class DocumentSourceCacheTest {
    @Test
    void productionPolicyLimitsBytesAndExpiresAfterTenMinutes() {
        Cache<String, byte[]> cache = DocumentParseWorker.SOURCE_CACHE;
        var eviction = cache.policy().eviction().orElseThrow();
        assertTrue(eviction.isWeighted());
        assertEquals(32L * 1024 * 1024, eviction.getMaximum());
        assertEquals(Duration.ofMinutes(10), cache.policy().expireAfterWrite().orElseThrow().getExpiresAfter());
    }

    @Test
    void totalWeightTriggersEvictionWithoutReproducingAnOutOfMemoryError() {
        Cache<String, byte[]> cache = DocumentParseWorker.newSourceCache(() -> 0L);
        var eviction = cache.policy().eviction().orElseThrow();
        eviction.setMaximum(1024);
        cache.put("1:0", new byte[700]);
        cache.put("2:0", new byte[700]);
        cache.cleanUp();

        assertEquals(1, cache.estimatedSize());
        assertTrue(eviction.weightedSize().orElseThrow() <= 1024);
    }

    @Test
    void aSingleSourceLargerThanTheCacheBudgetIsNotRetained() {
        Cache<String, byte[]> cache = DocumentParseWorker.newSourceCache(() -> 0L);
        cache.policy().eviction().orElseThrow().setMaximum(1024);
        cache.put("1:0", new byte[1025]);
        cache.cleanUp();

        assertNull(cache.getIfPresent("1:0"));
        assertEquals(0, cache.estimatedSize());
    }

    @Test
    void emptySourcesCannotBypassTheWeightBudget() {
        Cache<String, byte[]> cache = DocumentParseWorker.newSourceCache(() -> 0L);
        cache.policy().eviction().orElseThrow().setMaximum(2);
        for (int i = 0; i < 3; i++) cache.put(i + ":0", new byte[0]);
        cache.cleanUp();

        assertTrue(cache.estimatedSize() <= 2);
        assertEquals(cache.estimatedSize(), cache.policy().eviction().orElseThrow().weightedSize().orElseThrow());
    }

    @Test
    void accessDoesNotExtendTheTenMinuteWriteExpiry() {
        AtomicLong clock = new AtomicLong();
        Cache<String, byte[]> cache = DocumentParseWorker.newSourceCache(clock::get);
        byte[] source = new byte[]{1, 2, 3};
        cache.put("1:0", source);
        clock.set(Duration.ofMinutes(9).toNanos());
        assertSame(source, cache.getIfPresent("1:0"));

        clock.set(Duration.ofMinutes(10).toNanos() + 1);
        assertNull(cache.getIfPresent("1:0"));
        cache.cleanUp();
        assertEquals(0, cache.estimatedSize());
    }
}
