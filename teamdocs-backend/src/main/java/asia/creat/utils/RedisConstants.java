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

    //空值设置
    public static final String NULL_VALUE = "NULL";
    //一般过期时间
    public static final Duration COMMON_TTL = Duration.ofMinutes(30L);
    //空值的过期时间
    public static final Duration NULL_TTL = Duration.ofSeconds(60L);
    //最近访问文档过期时间
    public static final Duration RECENT_DOCUMENT_TTL = Duration.ofDays(30L);

    //登录限制窗口
    public static final Duration LOGIN_LIMIT_WINDOW = Duration.ofSeconds(60L);
    //注册限制窗口
    public static final Duration REGISTER_LIMIT_WINDOW = Duration.ofSeconds(60L);

    //最大登录尝试次数
    public static final Long MAX_LOGIN_ATTEMPTS = 10L;
    //最大注册尝试次数
    public static final Long MAX_REGISTER_ATTEMPTS = 5L;

    //最大随机秒数
    public static final Long MAX_RANDOM_TTL_SECONDS = 300L;

    //最近访问记录
    public static final Long MAX_RECENT_DOCUMENTS = 20L;



}
