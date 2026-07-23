package asia.creat.service;

import java.time.Duration;

public interface RateLimitService {

    boolean isRateLimited(String key, Duration limitWindow, long maxAttempts);
}
