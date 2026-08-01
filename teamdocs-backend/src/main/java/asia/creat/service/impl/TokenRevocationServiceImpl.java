package asia.creat.service.impl;

import asia.creat.common.exception.BusinessException;
import asia.creat.service.TokenRevocationService;
import asia.creat.utils.JWTUtils;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import static asia.creat.utils.RedisConstants.TOKEN_REVOKED_PREFIX;
import static asia.creat.utils.RedisConstants.TOKEN_REVOKED_PREFIX;
import static asia.creat.utils.RedisConstants.TOKEN_USER_INVALID_BEFORE_PREFIX;
import static asia.creat.utils.RedisConstants.TOKEN_USER_INVALID_BEFORE_PREFIX;

import java.time.Duration;
import java.util.Date;

import static asia.creat.utils.RedisConstants.TOKEN_REVOKED_PREFIX;
import static asia.creat.utils.RedisConstants.TOKEN_REVOKED_PREFIX;
import static asia.creat.utils.RedisConstants.TOKEN_USER_INVALID_BEFORE_PREFIX;
import static asia.creat.utils.RedisConstants.TOKEN_USER_INVALID_BEFORE_PREFIX;

@Service
public class TokenRevocationServiceImpl implements TokenRevocationService {
    private final StringRedisTemplate stringRedisTemplate;
    private final JWTUtils jwtUtils;
    private final long jwtExpirationMillis;

    public TokenRevocationServiceImpl(StringRedisTemplate stringRedisTemplate,
                                      JWTUtils jwtUtils,
                                      @Value("${jwt.expiration}") long jwtExpirationMillis) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.jwtUtils = jwtUtils;
        this.jwtExpirationMillis = jwtExpirationMillis;
    }

    @Override
    public void revoke(String token) {
        Claims claims = jwtUtils.parseToken(token);
        String tokenId = claims.getId();
        Date expiration = claims.getExpiration();
        if (tokenId == null || tokenId.isBlank() || expiration == null) {
            throw new BusinessException("Token 信息不完整");
        }

        long ttlMillis = expiration.getTime() - System.currentTimeMillis();
        if (ttlMillis <= 0) {
            throw new BusinessException("Token 已过期");
        }

        try {
            stringRedisTemplate.opsForValue().set(
                    TOKEN_REVOKED_PREFIX + tokenId,
                    "1",
                    Duration.ofMillis(ttlMillis)
            );
        } catch (RuntimeException e) {
            throw new BusinessException("退出登录失败，请稍后重试", e);
        }
    }

    @Override
    public boolean isRevoked(String tokenId) {
        Boolean revoked;
        try {
            revoked = stringRedisTemplate.hasKey(TOKEN_REVOKED_PREFIX + tokenId);
        } catch (RuntimeException e) {
            throw new BusinessException("Token 撤销状态校验失败", e);
        }

        if (revoked == null) {
            throw new BusinessException("Token 撤销状态校验无结果");
        }
        return revoked;
    }

    @Override
    public void invalidateAllForUser(Long userId) {
        if (userId == null) {
            throw new BusinessException("用户信息不完整");
        }
        try {
            stringRedisTemplate.opsForValue().set(
                    TOKEN_USER_INVALID_BEFORE_PREFIX + userId,
                    String.valueOf(System.currentTimeMillis()),
                    Duration.ofMillis(jwtExpirationMillis)
            );
        } catch (RuntimeException e) {
            throw new BusinessException("会话失效失败，请稍后重试", e);
        }
    }

    @Override
    public boolean isUserSessionInvalid(Long userId, Date issuedAt) {
        if (userId == null || issuedAt == null) {
            throw new BusinessException("Token 信息不完整");
        }
        String watermark;
        try {
            watermark = stringRedisTemplate.opsForValue().get(TOKEN_USER_INVALID_BEFORE_PREFIX + userId);
        } catch (RuntimeException e) {
            throw new BusinessException("Token 撤销状态校验失败", e);
        }
        if (watermark == null || watermark.isBlank()) {
            return false;
        }
        try {
            return issuedAt.getTime() < Long.parseLong(watermark);
        } catch (NumberFormatException e) {
            throw new BusinessException("Token 撤销状态校验失败", e);
        }
    }
}
