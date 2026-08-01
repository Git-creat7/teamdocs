package asia.creat.utils;

import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RedisConstants {
    public static final String CACHE_SPACE_PREFIX = "teamdocs:space:";
    public static final String LOGIN_LIMIT_PREFIX = "teamdocs:rate:login:";
    public static final String REGISTER_LIMIT_PREFIX = "teamdocs:rate:register:";
    public static final String RECENT_DOCUMENT_PREFIX = "teamdocs:user:recent:";
    public static final String TOKEN_REVOKED_PREFIX = "teamdocs:auth:revoked:";
    public static final String TOKEN_USER_INVALID_BEFORE_PREFIX = "teamdocs:auth:user-invalid-before:";
    public static final String NULL_VALUE = "NULL";

    public static final Duration COMMON_TTL = Duration.ofMinutes(30L);
    public static final Duration NULL_TTL = Duration.ofSeconds(60L);
    public static final Duration RECENT_DOCUMENT_TTL = Duration.ofDays(30L);
    public static final Duration LOGIN_LIMIT_WINDOW = Duration.ofSeconds(60L);
    public static final Duration REGISTER_LIMIT_WINDOW = Duration.ofSeconds(60L);
    public static final Long MAX_LOGIN_ATTEMPTS = 10L;
    public static final Long MAX_REGISTER_ATTEMPTS = 5L;
    public static final Long MAX_RANDOM_TTL_SECONDS = 300L;
    public static final Long MAX_RECENT_DOCUMENTS = 20L;

}
