package asia.creat.teamdocsbackend.service.impl;

import asia.creat.common.exception.BusinessException;
import asia.creat.service.impl.TokenRevocationServiceImpl;
import asia.creat.utils.JWTUtils;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Date;

import static asia.creat.utils.RedisConstants.TOKEN_REVOKED_PREFIX;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenRevocationServiceImplTest {
    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private JWTUtils jwtUtils;

    @Mock
    private Claims claims;

    private TokenRevocationServiceImpl tokenRevocationService;

    @BeforeEach
    void setUp() {
        tokenRevocationService = new TokenRevocationServiceImpl(stringRedisTemplate, jwtUtils);
    }

    @Test
    void revokeShouldStoreTokenIdUntilJwtExpiration() {
        Date expiration = new Date(System.currentTimeMillis() + 60_000L);
        when(jwtUtils.parseToken("token")).thenReturn(claims);
        when(claims.getId()).thenReturn("token-id");
        when(claims.getExpiration()).thenReturn(expiration);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        long maximumExpectedTtl = expiration.getTime() - System.currentTimeMillis();

        tokenRevocationService.revoke("token");

        long minimumExpectedTtl = expiration.getTime() - System.currentTimeMillis();
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(
                eq(TOKEN_REVOKED_PREFIX + "token-id"),
                eq("1"),
                ttlCaptor.capture()
        );
        long actualTtl = ttlCaptor.getValue().toMillis();
        assertTrue(actualTtl <= maximumExpectedTtl);
        assertTrue(actualTtl >= minimumExpectedTtl);
    }

    @Test
    void revokeShouldFailWhenRedisWriteFails() {
        Date expiration = new Date(System.currentTimeMillis() + 60_000L);
        when(jwtUtils.parseToken("token")).thenReturn(claims);
        when(claims.getId()).thenReturn("token-id");
        when(claims.getExpiration()).thenReturn(expiration);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RuntimeException("Redis unavailable"))
                .when(valueOperations)
                .set(eq(TOKEN_REVOKED_PREFIX + "token-id"), eq("1"), any(Duration.class));

        assertThrows(BusinessException.class, () -> tokenRevocationService.revoke("token"));
    }

    @Test
    void revocationCheckShouldFailWhenRedisReadFails() {
        when(stringRedisTemplate.hasKey(TOKEN_REVOKED_PREFIX + "token-id"))
                .thenThrow(new RuntimeException("Redis unavailable"));

        assertThrows(BusinessException.class,
                () -> tokenRevocationService.isRevoked("token-id"));
    }

    @Test
    void revocationCheckShouldFailWhenRedisReturnsNoResult() {
        when(stringRedisTemplate.hasKey(TOKEN_REVOKED_PREFIX + "token-id"))
                .thenReturn(null);

        assertThrows(BusinessException.class,
                () -> tokenRevocationService.isRevoked("token-id"));
    }
}
