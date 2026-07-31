package com.tpverp.backend.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AesGcmSecretProtectorTest {

    @Test
    void protectsWithANonceAndRestoresTheOriginalValue() {
        var protector = new AesGcmSecretProtector(key((byte) 0x2a));
        var plaintext = "portable-installation-secret".getBytes(StandardCharsets.UTF_8);

        var first = protector.protect(plaintext);
        var second = protector.protect(plaintext);

        assertThat(first).isNotEqualTo(plaintext);
        assertThat(second).isNotEqualTo(first);
        assertThat(protector.unprotect(first)).isEqualTo(plaintext);
    }

    @Test
    void rejectsTamperedCiphertext() {
        var protector = new AesGcmSecretProtector(key((byte) 0x2a));
        var protectedValue = protector.protect("secret".getBytes(StandardCharsets.UTF_8));
        protectedValue[protectedValue.length - 1] ^= 1;

        assertThatThrownBy(() -> protector.unprotect(protectedValue))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requiresA256BitKey() {
        assertThatThrownBy(() -> new AesGcmSecretProtector(new byte[16]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    private static byte[] key(byte value) {
        var key = new byte[32];
        Arrays.fill(key, value);
        return key;
    }
}
