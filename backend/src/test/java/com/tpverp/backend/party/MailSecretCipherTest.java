package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MailSecretCipherTest {
    private final MailSecretCipher cipher = new MailSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));

    @Test void roundTripUsesRandomNonceAndCompanyBinding() {
        var company = UUID.randomUUID();
        var encrypted = cipher.encrypt("smtp-secret", company);
        assertThat(encrypted).doesNotContain("smtp-secret").isNotEqualTo(cipher.encrypt("smtp-secret", company));
        assertThat(cipher.decrypt(encrypted, company)).isEqualTo("smtp-secret");
        assertThatThrownBy(() -> cipher.decrypt(encrypted, UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
    }
    @Test void refusesPlaintextAndMissingKey() {
        assertThatThrownBy(() -> cipher.decrypt("legacy-secret")).hasMessage("message.smtp.credentials_require_resave");
        assertThatThrownBy(() -> new MailSecretCipher("").encrypt("secret")).hasMessage("message.smtp.encryption_key_missing");
    }
    @Test void rejectsCorruptionAndInvalidKey() {
        var encrypted = cipher.encrypt("secret");
        assertThatThrownBy(() -> cipher.decrypt(encrypted.substring(0,encrypted.length()-3)+"AAA"))
                .hasMessage("message.smtp.credential_decryption_failed");
        assertThatThrownBy(() -> new MailSecretCipher("abcd")).hasMessage("message.smtp.encryption_key_invalid");
    }
}
