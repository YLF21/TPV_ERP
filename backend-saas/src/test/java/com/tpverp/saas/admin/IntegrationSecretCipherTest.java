package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IntegrationSecretCipherTest {

    private static final String TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    void encryptsWithRandomAuthenticatedCiphertext() {
        var cipher = new IntegrationSecretCipher(TEST_KEY);

        String first = cipher.encrypt("merchant-secret");
        String second = cipher.encrypt("merchant-secret");

        assertThat(first).startsWith("v1:").isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("merchant-secret");
        assertThat(cipher.decrypt(second)).isEqualTo("merchant-secret");
    }

    @Test
    void refusesSecretsWhenNoKeyWasConfigured() {
        var cipher = new IntegrationSecretCipher("");

        assertThatThrownBy(() -> cipher.encrypt("merchant-secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TPV_SAAS_SECRET_ENCRYPTION_KEY");
    }
}
