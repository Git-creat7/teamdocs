package asia.creat.teamdocsbackend.service.impl;

import asia.creat.service.impl.RateLimitServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitServiceImplTest {
    private static final String KEY = "teamdocs:rate:login:127.0.0.1";
    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final long MAX_ATTEMPTS = 10L;

    // 模拟 Redis 模板，测试不会连接真实 Redis。
    @Mock
    private StringRedisTemplate stringRedisTemplate;

    private RateLimitServiceImpl rateLimitService;

    @BeforeEach
    void setUp() {
        rateLimitService = new RateLimitServiceImpl(stringRedisTemplate);
    }

    // 第一次请求处于限制范围内，不应被限流。
    @Test
    void shouldAllowFirstAttempt() {
        mockCurrentAttempts(1L);

        boolean limited = rateLimitService.isRateLimited(KEY, WINDOW, MAX_ATTEMPTS);

        assertFalse(limited);
    }

    // 第十次请求刚好到达上限，仍应被允许。
    @Test
    void shouldAllowAttemptAtLimit() {
        mockCurrentAttempts(10L);

        boolean limited = rateLimitService.isRateLimited(KEY, WINDOW, MAX_ATTEMPTS);

        assertFalse(limited);
    }

    // 第十一次请求超过上限，应被限流。
    @Test
    void shouldRejectAttemptAboveLimit() {
        mockCurrentAttempts(11L);

        boolean limited = rateLimitService.isRateLimited(KEY, WINDOW, MAX_ATTEMPTS);

        assertTrue(limited);
    }

    // Redis 返回 null 时按基础设施异常处理，采用故障放行策略。
    @Test
    void shouldAllowWhenRedisReturnsNull() {
        mockCurrentAttempts(null);

        boolean limited = rateLimitService.isRateLimited(KEY, WINDOW, MAX_ATTEMPTS);

        assertFalse(limited);
    }

    // Redis 抛异常时不能阻断登录，应记录日志并放行。
    @Test
    void shouldAllowWhenRedisFails() {
        when(stringRedisTemplate.execute(
                any(RedisScript.class),
                eq(Collections.singletonList(KEY)),
                eq("60")
        )).thenThrow(new RuntimeException("Redis exception"));

        boolean limited = rateLimitService.isRateLimited(KEY, WINDOW, MAX_ATTEMPTS);

        assertFalse(limited);
    }

    // Lua 的 KEYS[1] 和 ARGV[1] 分别对应完整 Key 与窗口秒数。
    private void mockCurrentAttempts(Long attempts) {
        when(stringRedisTemplate.execute(
                any(RedisScript.class),
                eq(Collections.singletonList(KEY)),
                eq("60")
        )).thenReturn(attempts);
    }
}
