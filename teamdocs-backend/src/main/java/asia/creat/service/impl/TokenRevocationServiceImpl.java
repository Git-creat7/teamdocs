package asia.creat.service.impl;

import asia.creat.common.exception.BusinessException;
import asia.creat.service.TokenRevocationService;
import asia.creat.utils.JWTUtils;
import io.jsonwebtoken.Claims;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Date;

import static asia.creat.utils.RedisConstants.TOKEN_REVOKED_PREFIX;

@Service
public class TokenRevocationServiceImpl implements TokenRevocationService {
    private final StringRedisTemplate stringRedisTemplate;
    private final JWTUtils jwtUtils;

    public TokenRevocationServiceImpl(StringRedisTemplate stringRedisTemplate, JWTUtils jwtUtils) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.jwtUtils = jwtUtils;
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
}
