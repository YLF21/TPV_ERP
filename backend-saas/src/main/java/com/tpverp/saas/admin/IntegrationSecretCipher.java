package com.tpverp.saas.admin;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class IntegrationSecretCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_LENGTH = 12;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public IntegrationSecretCipher(@Value("${tpv.saas.secrets.encryption-key:}") String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            this.key = null;
            return;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedKey.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("TPV_SAAS_SECRET_ENCRYPTION_KEY debe estar en Base64", exception);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException("TPV_SAAS_SECRET_ENCRYPTION_KEY debe contener exactamente 32 bytes");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    public String encrypt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        requireKey();
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(value.trim().getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array());
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo cifrar el secreto de integracion", exception);
        }
    }

    public String decrypt(String encryptedValue) {
        if (encryptedValue == null || encryptedValue.isBlank()) {
            return null;
        }
        requireKey();
        if (!encryptedValue.startsWith(PREFIX)) {
            throw new IllegalStateException("Formato de secreto de integracion no soportado");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(encryptedValue.substring(PREFIX.length()));
            if (payload.length <= IV_LENGTH) {
                throw new IllegalArgumentException("payload");
            }
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo descifrar el secreto de integracion", exception);
        }
    }

    public boolean configured() {
        return key != null;
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "TPV_SAAS_SECRET_ENCRYPTION_KEY es obligatorio para gestionar secretos de integracion");
        }
    }
}
