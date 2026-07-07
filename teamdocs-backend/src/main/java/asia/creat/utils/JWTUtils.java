package asia.creat.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

@Component
public class JWTUtils {

    @Value("${jwt.secret}")
    private  String secret;

    @Value("${jwt.expiration}")
    private  long expirationTime;

    private  SecretKey getSecretKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }


    public String generateJWT(Map<String, Object> claims) {
        return Jwts.builder()
                .claims(claims)           // 0.11 正确：无 set
                .expiration(new Date(System.currentTimeMillis() + expirationTime))
                .signWith(getSecretKey()) // 0.11 正确
                .compact();
    }


    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getSecretKey()) // 0.11 正确验证方式
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException e) {
            throw new RuntimeException("Token 无效或已过期");
        }
    }
}