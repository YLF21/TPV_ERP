package com.tpverp.backend.installation;

import com.tpverp.backend.licensing.Licencia;
import com.tpverp.backend.licensing.LicenciaRepository;
import com.tpverp.backend.shared.access.OperationalMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import org.springframework.transaction.annotation.Transactional;

public class InstallationStatusService {

    private final InstalacionRepository instalacionRepository;
    private final LicenciaRepository licenciaRepository;
    private final Clock clock;

    public InstallationStatusService(
            InstalacionRepository instalacionRepository,
            LicenciaRepository licenciaRepository,
            Clock clock) {
        this.instalacionRepository = instalacionRepository;
        this.licenciaRepository = licenciaRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public InstallationStatus status() {
        Instalacion installation = currentInstallation();
        Instant now = Instant.now(clock);
        Licencia activeLicense = licenciaRepository.findAll().stream()
                .filter(Licencia::isActiva)
                .findFirst()
                .orElse(null);
        OperationalMode mode;
        if (activeLicense != null
                && !now.isBefore(activeLicense.getValidaDesde())
                && now.isBefore(activeLicense.getValidaHasta())) {
            mode = OperationalMode.LICENSED;
        } else if (activeLicense == null && now.isBefore(installation.getDemoHasta())) {
            mode = OperationalMode.DEMO;
        } else {
            mode = OperationalMode.RESTRICTED;
        }
        return new InstallationStatus(
                installation.getId(),
                installation.getReferencia(),
                installation.getCreadaEn(),
                installation.getDemoHasta(),
                mode,
                activeLicense == null ? null : activeLicense.getReferencia());
    }

    @Transactional(readOnly = true)
    public LicenseRequest licenseRequest() {
        Instalacion installation = currentInstallation();
        String body = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(Base64.getDecoder().decode(installation.getPublicKey()));
        String pem = "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----";
        return new LicenseRequest(
                installation.getId().toString(),
                installation.getReferencia(),
                pem);
    }

    private Instalacion currentInstallation() {
        return instalacionRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("La instalacion no esta inicializada"));
    }

    public record InstallationStatus(
            java.util.UUID id,
            String reference,
            Instant createdAt,
            Instant demoUntil,
            OperationalMode mode,
            String activeLicenseReference) {
    }

    public record LicenseRequest(String id, String reference, String publicKey) {
    }
}
