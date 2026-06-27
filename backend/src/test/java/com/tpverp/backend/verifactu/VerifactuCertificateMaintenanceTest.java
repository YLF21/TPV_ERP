package com.tpverp.backend.verifactu;

import static com.tpverp.backend.audit.AuditResult.EXITO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VerifactuCertificateMaintenanceTest {

    private static final Instant NOW = Instant.parse("2026-06-23T10:00:00Z");

    @Test
    void recordsExpiryWarningOnlyOncePerDay() {
        var fixture = fixture();
        var active = certificate(NOW.plusSeconds(20L * 24 * 60 * 60));
        when(fixture.certificates.findAllByStatus(ManagedCertificateStatus.ACTIVO))
                .thenReturn(List.of(active));

        fixture.service.checkExpiry();
        fixture.service.checkExpiry();

        assertThat(active.getLastWarningDate()).isEqualTo(NOW.atZone(ZoneOffset.UTC).toLocalDate());
        verify(fixture.audit, times(1)).recordSystem(
                any(), eq("VERIFACTU_CERTIFICATE_EXPIRY_WARNING"), eq(EXITO), any());
    }

    @Test
    void purgesPreviousSecretAfterOneYearAndKeepsMetadata() {
        var fixture = fixture();
        var previous = certificate(NOW.plusSeconds(3600));
        previous.markPrevious(NOW.minusSeconds(366L * 24 * 60 * 60), UUID.randomUUID());
        when(fixture.certificates.findAllByStatusAndReplacedAtBefore(
                ManagedCertificateStatus.ANTERIOR,
                NOW.minusSeconds(365L * 24 * 60 * 60)))
                .thenReturn(List.of(previous));

        fixture.service.purgePrevious();

        verify(fixture.secrets).delete("secret.dpapi");
        assertThat(previous.getStatus()).isEqualTo(ManagedCertificateStatus.ELIMINADO);
        assertThat(previous.getFingerprint()).isEqualTo("A".repeat(64));
        verify(fixture.audit).recordSystem(
                any(), eq("VERIFACTU_CERTIFICATE_PURGED"), eq(EXITO), any());
    }

    private static Fixture fixture() {
        var certificates = mock(ManagedVerifactuCertificateRepository.class);
        var secrets = mock(VerifactuCertificateSecretStore.class);
        var audit = mock(AuditService.class);
        var stores = mock(StoreRepository.class);
        when(stores.findByEmpresaId(any())).thenReturn(List.of(mock(Store.class)));
        return new Fixture(
                certificates, secrets, audit,
                new VerifactuCertificateMaintenance(
                        certificates, secrets, audit, stores,
                        Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static ManagedVerifactuCertificate certificate(Instant validUntil) {
        return ManagedVerifactuCertificate.active(
                UUID.randomUUID(), "CN=Company", "CN=AC", "1234", "B12345674",
                NOW.minusSeconds(60), validUntil, "A".repeat(64), new byte[] {1},
                "secret.dpapi", NOW.minusSeconds(120), UUID.randomUUID());
    }

    private record Fixture(
            ManagedVerifactuCertificateRepository certificates,
            VerifactuCertificateSecretStore secrets,
            AuditService audit,
            VerifactuCertificateMaintenance service) {
    }
}
