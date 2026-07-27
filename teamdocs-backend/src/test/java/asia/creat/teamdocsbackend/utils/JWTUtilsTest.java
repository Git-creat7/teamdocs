package asia.creat.teamdocsbackend.utils;

import asia.creat.utils.JWTUtils;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JWTUtilsTest {
    private JWTUtils jwtUtils;

    @BeforeEach
    void setUp() {
        jwtUtils = new JWTUtils();
        ReflectionTestUtils.setField(
                jwtUtils,
                "secret",
                "0123456789012345678901234567890123456789"
        );
        ReflectionTestUtils.setField(jwtUtils, "expirationTime", 60_000L);
    }

    @Test
    void generatedTokenShouldContainJwtIdAndIssuedAt() {
        long beforeGeneration = System.currentTimeMillis();

        String token = jwtUtils.generateJWT(Map.of("userId", 7L, "username", "alice"));
        Claims claims = jwtUtils.parseToken(token);

        assertNotNull(claims.getId());
        assertDoesNotThrow(() -> UUID.fromString(claims.getId()));
        assertNotNull(claims.getIssuedAt());
        assertTrue(claims.getIssuedAt().getTime() >= beforeGeneration - 1_000L);
        assertTrue(claims.getIssuedAt().getTime() <= System.currentTimeMillis());
    }
}
