package asia.creat.service;

import java.util.Date;

public interface TokenRevocationService {
    void revoke(String token);

    boolean isRevoked(String tokenId);

    void invalidateAllForUser(Long userId);

    boolean isUserSessionInvalid(Long userId, Date issuedAt);
}
