package asia.creat.teamdocsbackend.utils;

import asia.creat.utils.CacheClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheClientTest {
    // 模拟底层 Redis 模板，测试期间不会连接真实 Redis。
    @Mock
    private StringRedisTemplate stringRedisTemplate;

    // opsForValue() 返回的字符串操作对象也需要单独模拟。
    @Mock
    private ValueOperations<String, String> valueOperations;

    // Mockito 会把上面的 StringRedisTemplate 注入待测试的 CacheClient。
    @InjectMocks
    private CacheClient cacheClient;

    @BeforeEach
    void setUp() {
        // 删除测试不调用 opsForValue()，因此将这条公共模拟设为宽松模式。
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // Redis 正常返回时，CacheClient 应原样返回缓存内容。
    @Test
    void getShouldReturnCachedValue() {
        // 准备：规定模拟 Redis 读取 testKey 时返回 testString。
        when(valueOperations.get("testKey")).thenReturn("testString");

        // 执行：调用真正需要测试的 CacheClient。
        String result = cacheClient.get("testKey");

        // 断言：结果正确，并且底层读取只被调用了一次。
        assertEquals("testString", result);
        verify(valueOperations).get("testKey");
    }

    // Redis 读取异常时，CacheClient 应降级为缓存未命中，而不是继续抛异常。
    @Test
    void getShouldReturnNullWhenRedisFails() {
        when(valueOperations.get("errorKey"))
                .thenThrow(new RuntimeException("Redis exception"));

        String result = cacheClient.get("errorKey");

        assertNull(result);
        verify(valueOperations).get("errorKey");
    }

    // 对象缓存写入失败时，异常应被 CacheClient 隔离。
    @Test
    void setShouldNotThrowWhenRedisFails() {
        // CacheClient 会先把对象转成 JSON，再调用带 Duration 的 set 方法。
        doThrow(new RuntimeException("Redis exception"))
                .when(valueOperations)
                .set(eq("errorKey"), anyString(), any(Duration.class));

        assertDoesNotThrow(() ->
                cacheClient.set("errorKey", "errorValue", Duration.ofMinutes(1))
        );
    }

    // 原始字符串写入失败时，同样不能阻断业务。
    @Test
    void setStringShouldNotThrowWhenRedisFails() {
        doThrow(new RuntimeException("Redis exception"))
                .when(valueOperations)
                .set(eq("errorKey"), anyString(), any(Duration.class));

        assertDoesNotThrow(() ->
                cacheClient.setString("errorKey", "NULL", Duration.ofMinutes(1))
        );
    }

    // 缓存删除失败时，数据库更新或删除操作仍应保持成功。
    @Test
    void deleteShouldNotThrowWhenRedisFails() {
        when(stringRedisTemplate.delete("errorKey"))
                .thenThrow(new RuntimeException("Redis exception"));

        assertDoesNotThrow(() -> cacheClient.delete("errorKey"));
        verify(stringRedisTemplate).delete("errorKey");
    }
}
