package asia.creat.teamdocsbackend.model;

import asia.creat.model.ModelSecretCipher;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class ModelSecretCipherTest {
    private final ModelSecretCipher cipher = new ModelSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void encryptsWithUniqueNoncesAndBindsOwnerAndPurpose() {
        String first = cipher.encrypt(1, "config", "private-key");
        String second = cipher.encrypt(1, "config", "private-key");
        assertNotEquals(first, second);
        assertFalse(first.contains("private-key"));
        assertEquals("private-key", cipher.decrypt(1, "config", first));
        assertThrows(RuntimeException.class, () -> cipher.decrypt(2, "config", first));
        assertThrows(RuntimeException.class, () -> cipher.decrypt(1, "run", first));
        byte[] bytes = Base64.getDecoder().decode(first);
        bytes[15] ^= 1;
        assertThrows(RuntimeException.class, () -> cipher.decrypt(1, "config", Base64.getEncoder().encodeToString(bytes)));
    }

    @Test
    void missingOrInvalidMasterKeyDoesNotPermitPlaintextStorage() {
        for (String key : new String[]{"", "short", Base64.getEncoder().encodeToString(new byte[16])}) {
            var missing = new ModelSecretCipher(key);
            assertFalse(missing.ready());
            assertThrows(RuntimeException.class, () -> missing.encrypt(1, "config", "private-key"));
        }
    }
}
