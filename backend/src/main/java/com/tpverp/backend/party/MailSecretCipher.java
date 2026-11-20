package com.tpverp.backend.party;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Authenticated encryption; the deployment key is never stored in the database. */
@Component
public class MailSecretCipher {
    private static final String PREFIX = "enc:v1:";
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public MailSecretCipher(@Value("${tpv.mail.encryption-key:}") String encodedKey) {
        try {
            key = encodedKey == null || encodedKey.isBlank() ? null : Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("message.smtp.encryption_key_invalid");
        }
        if (key != null && key.length != 32) throw new IllegalArgumentException("message.smtp.encryption_key_invalid");
    }

    public boolean isConfigured() { return key != null; }
    public boolean isEncrypted(String value) { return value != null && value.startsWith(PREFIX); }
    public String encrypt(String value) { return encrypt(value, null); }
    public String decrypt(String value) { return decrypt(value, null); }

    public String encrypt(String value, UUID companyId) {
        if (value == null) return null;
        requireKey();
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            var cipher = cipher(Cipher.ENCRYPT_MODE, nonce, companyId);
            return PREFIX + Base64.getEncoder().encodeToString(nonce) + ":"
                    + Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("message.smtp.credential_encryption_failed");
        }
    }

    public String decrypt(String value, UUID companyId) {
        if (value == null) return null;
        requireKey();
        if (!isEncrypted(value)) throw new IllegalStateException("message.smtp.credentials_require_resave");
        try {
            String[] parts = value.substring(PREFIX.length()).split(":", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            byte[] nonce = Base64.getDecoder().decode(parts[0]);
            if (nonce.length != 12) throw new IllegalArgumentException();
            return new String(cipher(Cipher.DECRYPT_MODE, nonce, companyId)
                    .doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("message.smtp.credential_decryption_failed");
        }
    }

    private Cipher cipher(int mode, byte[] nonce, UUID companyId) throws GeneralSecurityException {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("espos-mail-v1:" + (companyId == null ? "oauth" : companyId))
                .getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
    private void requireKey() {
        if (!isConfigured()) throw new IllegalStateException("message.smtp.encryption_key_missing");
    }
}
