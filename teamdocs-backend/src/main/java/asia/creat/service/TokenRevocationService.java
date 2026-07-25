package asia.creat.service;

public interface TokenRevocationService {
    void revoke(String token);

    boolean isRevoked(String tokenId);
}
