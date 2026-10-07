package asia.creat.model;

import asia.creat.common.exception.BusinessException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 使用独立主密钥加密用户凭据，密文绑定用户和用途，不能跨用户复用。 */
@Component
public class ModelSecretCipher {
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public ModelSecretCipher(@Value("${MODEL_CONFIG_ENCRYPTION_KEY:}") String encoded) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException ignored) {
            decoded = new byte[0];
        }
        key = decoded.length == 32 ? decoded : null;
    }

    public boolean ready() { return key != null; }

    public String encrypt(long userId, String purpose, String value) {
        requireKey();
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, userId, purpose);
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce).put(encrypted).array());
        } catch (Exception error) {
            throw new BusinessException("模型凭据加密失败");
        }
    }

    public String decrypt(long userId, String purpose, String encoded) {
        requireKey();
        try {
            byte[] data = Base64.getDecoder().decode(encoded);
            if (data.length < 28) throw new IllegalArgumentException();
            byte[] nonce = Arrays.copyOf(data, 12);
            byte[] plain = cipher(Cipher.DECRYPT_MODE, nonce, userId, purpose).doFinal(data, 12, data.length - 12);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new BusinessException("模型凭据无法解密，请重新保存配置或联系管理员检查主密钥");
        }
    }

    private Cipher cipher(int mode, byte[] nonce, long userId, String purpose) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("v1:" + userId + ":" + purpose).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private void requireKey() {
        if (!ready()) throw new BusinessException("管理员尚未配置有效的模型凭据加密主密钥");
    }
}
