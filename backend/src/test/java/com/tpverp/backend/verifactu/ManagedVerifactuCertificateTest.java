package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManagedVerifactuCertificateTest {

    private static final Instant IMPORTED = Instant.parse("2026-06-23T09:00:00Z");
    private static final UUID USER_ID = UUID.randomUUID();

    @Test
    void retainsPublicMetadataWhenPreviousSecretIsRemoved() {
        var certificate = active();
        var replacedAt = IMPORTED.plusSeconds(60);

        certificate.markPrevious(replacedAt, USER_ID);
        certificate.removeSecret(replacedAt.plusSeconds(365L * 24 * 60 * 60));

        assertThat(certificate.getStatus()).isEqualTo(ManagedCertificateStatus.ELIMINADO);
        assertThat(certificate.getSecretPath()).isNull();
        assertThat(certificate.getFingerprint()).isEqualTo("A".repeat(64));
        assertThat(certificate.getTaxId()).isEqualTo("B12345674");
        assertThat(certificate.getReplacedAt()).isEqualTo(replacedAt);
        assertThat(certificate.getDeletedAt()).isNotNull();
    }

    @Test
    void cannotReactivateOrReplaceCertificateTwice() {
        var certificate = active();
        certificate.markPrevious(IMPORTED.plusSeconds(60), USER_ID);

        assertThatThrownBy(() -> certificate.markPrevious(
                IMPORTED.plusSeconds(120), USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Solo un certificado activo puede sustituirse");
    }

    private static ManagedVerifactuCertificate active() {
        return ManagedVerifactuCertificate.active(
                UUID.randomUUID(), "CN=Empresa", "CN=AC", "1234",
                "B12345674", IMPORTED.minusSeconds(60),
                IMPORTED.plusSeconds(365L * 24 * 60 * 60), "A".repeat(64),
                new byte[] {1, 2, 3}, "empresa/cert/private-key.dpapi",
                IMPORTED, USER_ID);
    }
}
