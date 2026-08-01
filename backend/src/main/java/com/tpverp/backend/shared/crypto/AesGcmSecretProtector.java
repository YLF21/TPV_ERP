package com.tpverp.backend.shared.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AesGcmSecretProtector implements SecretProtector {

    private static final byte FORMAT_VERSION = 1;
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random;

    public static AesGcmSecretProtector fromBase64(String encodedKey) {
        try {
            return new AesGcmSecretProtector(Base64.getDecoder().decode(encodedKey));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "tpv.installation.portable-secret-key debe ser Base64 valido", exception);
        }
    }

    public AesGcmSecretProtector(byte[] keyBytes) {
        this(keyBytes, new SecureRandom());
    }

    AesGcmSecretProtector(byte[] keyBytes, SecureRandom random) {
        if (keyBytes == null || keyBytes.length != KEY_BYTES) {
            throw new IllegalArgumentException("La clave AES debe contener exactamente 32 bytes");
        }
        this.key = new SecretKeySpec(keyBytes.clone(), "AES");
        this.random = random;
    }

    @Override
    public byte[] protect(byte[] plaintext) {
        requireValue(plaintext);
        var nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            var ciphertext = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(1 + nonce.length + ciphertext.length)
                    .put(FORMAT_VERSION)
                    .put(nonce)
                    .put(ciphertext)
                    .array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("No se pudo proteger el secreto con AES-GCM", exception);
        }
    }

    @Override
    public byte[] unprotect(byte[] protectedValue) {
        if (protectedValue == null || protectedValue.length <= 1 + NONCE_BYTES + TAG_BITS / 8) {
            throw new IllegalArgumentException("El secreto AES-GCM protegido no es valido");
        }
        var buffer = ByteBuffer.wrap(protectedValue);
        if (buffer.get() != FORMAT_VERSION) {
            throw new IllegalArgumentException("Version de secreto AES-GCM no compatible");
        }
        var nonce = new byte[NONCE_BYTES];
        buffer.get(nonce);
        var ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("No se pudo abrir el secreto AES-GCM", exception);
        }
    }

    private static void requireValue(byte[] value) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException("El secreto es obligatorio");
        }
    }
}
