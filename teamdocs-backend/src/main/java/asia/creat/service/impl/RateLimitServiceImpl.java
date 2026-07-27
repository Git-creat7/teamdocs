package asia.creat.service.impl;

import asia.creat.service.RateLimitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;

@Service
@Slf4j
public class RateLimitServiceImpl implements RateLimitService {
    private final StringRedisTemplate stringRedisTemplate;

    public RateLimitServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private final DefaultRedisScript<Long> script;

    {
        script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/window_rate_limit.lua"));
        script.setResultType(Long.class);
    }

    @Override
    public boolean isRateLimited(String key, Duration limitWindow, long maxAttempts) {
        Long attempts = null;
        try {
            attempts = stringRedisTemplate.execute(
                    script,
                    Collections.singletonList(key),
                    String.valueOf(limitWindow.toSeconds())
            );
        } catch (Exception e) {
            log.warn("Redis 异常", e);
        }
        if(attempts!=null && attempts > maxAttempts) return true;
        return false;
    }
}
