package com.tpverp.backend.verifactu;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.organization.StoreRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class VerifactuCertificateMaintenance {

    private static final Duration WARNING_WINDOW = Duration.ofDays(30);
    private static final Duration RETENTION = Duration.ofDays(365);

    private final ManagedVerifactuCertificateRepository certificates;
    private final VerifactuCertificateSecretStore secrets;
    private final AuditService audit;
    private final StoreRepository stores;
    private final Clock clock;

    public VerifactuCertificateMaintenance(
            ManagedVerifactuCertificateRepository certificates,
            VerifactuCertificateSecretStore secrets,
            AuditService audit,
            StoreRepository stores,
            Clock clock) {
        this.certificates = certificates;
        this.secrets = secrets;
        this.audit = audit;
        this.stores = stores;
        this.clock = clock;
    }

    // Genera como maximo un aviso diario por certificado proximo a caducar.
    @Scheduled(cron = "${tpv.verifactu.certificate-warning-cron:0 0 9 * * *}")
    @Transactional
    public void checkExpiry() {
        var now = Instant.now(clock);
        var today = now.atZone(ZoneOffset.UTC).toLocalDate();
        var warningLimit = now.plus(WARNING_WINDOW);
        for (var certificate : certificates.findAllByStatus(ManagedCertificateStatus.ACTIVO)) {
            if (certificate.getValidUntil().isAfter(warningLimit)
                    || today.equals(certificate.getLastWarningDate())) {
                continue;
            }
            certificate.markWarning(today);
            certificates.save(certificate);
            audit.recordSystem(
                    firstStore(certificate),
                    "VERIFACTU_CERTIFICATE_EXPIRY_WARNING",
                    AuditResult.EXITO,
                    details(certificate));
        }
    }

    // Elimina mensualmente claves anteriores cuyo ano de retencion ha finalizado.
    @Scheduled(cron = "${tpv.verifactu.certificate-purge-cron:0 0 3 1 * *}")
    @Transactional
    public void purgePrevious() {
        var now = Instant.now(clock);
        for (var certificate : certificates.findAllByStatusAndReplacedAtBefore(
                ManagedCertificateStatus.ANTERIOR, now.minus(RETENTION))) {
            secrets.delete(certificate.getSecretPath());
            certificate.removeSecret(now);
            certificates.save(certificate);
            audit.recordSystem(
                    firstStore(certificate),
                    "VERIFACTU_CERTIFICATE_PURGED",
                    AuditResult.EXITO,
                    details(certificate));
        }
    }

    private com.tpverp.backend.organization.Store firstStore(
            ManagedVerifactuCertificate certificate) {
        return stores.findByEmpresaId(certificate.getCompanyId()).stream()
                .findFirst()
                .orElse(null);
    }

    private static Map<String, Object> details(ManagedVerifactuCertificate certificate) {
        return Map.of(
                "certificateId", certificate.getId(),
                "taxId", certificate.getTaxId(),
                "fingerprint", certificate.getFingerprint(),
                "validUntil", certificate.getValidUntil().toString());
    }
}
