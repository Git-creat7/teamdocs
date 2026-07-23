package asia.creat.utils;

import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RedisConstants {
    public static final String CACHE_SPACE_PREFIX = "teamdocs:space:";
    public static final String LOGIN_LIMIT_PREFIX = "teamdocs:rate:login:";



    public static final String NULL_VALUE = "NULL";
    public static final Duration COMMON_TTL = Duration.ofMinutes(30L);
    public static final Duration NULL_TTL = Duration.ofSeconds(60L);

    public static final Duration LOGIN_LIMIT_WINDOW = Duration.ofSeconds(60L);
    public static final Long MAX_LOGIN_ATTEMPTS = 10L;

    public static final Long MAX_RANDOM_TTL_SECONDS = 300L;
}
