package com.tpverp.backend.installation;

import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.licensing.LicenseSaasCacheAuthenticator;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CompanyRepository;
import com.tpverp.backend.shared.access.OperationalMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class InstallationStatusService {

    private final InstallationRepository instalacionRepository;
    private final LicenseRepository licenciaRepository;
    private final CompanyRepository empresaRepository;
    private final Clock clock;
    private final boolean unlicensedDevelopmentAccessEnabled;
    private final LicenseSaasCacheAuthenticator cacheAuthenticator;

    public InstallationStatusService(
            InstallationRepository instalacionRepository,
            LicenseRepository licenciaRepository,
            CompanyRepository empresaRepository,
            Clock clock,
            boolean unlicensedDevelopmentAccessEnabled,
            LicenseSaasCacheAuthenticator cacheAuthenticator) {
        this.instalacionRepository = instalacionRepository;
        this.licenciaRepository = licenciaRepository;
        this.empresaRepository = empresaRepository;
        this.clock = clock;
        this.unlicensedDevelopmentAccessEnabled = unlicensedDevelopmentAccessEnabled;
        this.cacheAuthenticator = cacheAuthenticator;
    }

    @Transactional(readOnly = true)
    public InstallationStatus status() {
        Installation installation = currentInstallation();
        License activeLicense = licenciaRepository.findAll().stream()
                .filter(License::isActiva)
                .findFirst()
                .orElse(null);
        return status(installation, activeLicense);
    }

    @Transactional(readOnly = true)
    public InstallationStatus statusForStore(UUID storeId) {
        if (storeId == null) {
            throw new IllegalArgumentException("storeId es obligatorio");
        }
        Installation installation = currentInstallation();
        License activeLicense = licenciaRepository
                .findFirstByTienda_IdAndActivaTrueOrderByValidaDesdeDesc(storeId)
                .orElse(null);
        return status(installation, activeLicense);
    }

    private InstallationStatus status(Installation installation, License activeLicense) {
        Instant now = Instant.now(clock);
        OperationalMode mode;
        if (activeLicense == null) {
            mode = unlicensedDevelopmentAccessEnabled && isDemoCompany()
                    ? OperationalMode.DEVELOPMENT
                    : OperationalMode.UNLINKED;
        } else if (activeLicense.isSaasLinked()
                && (activeLicense.getFormatVersion()
                        != LicenseSaasCacheAuthenticator.AUTHENTICATED_FORMAT_VERSION
                    || !cacheAuthenticator.isAuthentic(activeLicense))) {
            mode = OperationalMode.RESTRICTED;
        } else if (activeLicense.isOperationalAt(now)) {
            mode = activeLicense.requiresOfflineExpiredWarningAt(now)
                    ? OperationalMode.OFFLINE
                    : OperationalMode.LICENSED;
        } else {
            mode = OperationalMode.RESTRICTED;
        }
        boolean organizationProvisioned = activeLicense != null || empresaRepository.count() > 0;
        return new InstallationStatus(
                installation.getId(),
                installation.getReferencia(),
                installation.getCreadaEn(),
                installation.getDemoHasta(),
                mode,
                activeLicense == null ? null : activeLicense.getReferencia(),
                organizationProvisioned);
    }

    @Transactional(readOnly = true)
    public LicenseRequest licenseRequest() {
        Installation installation = currentInstallation();
        String body = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(Base64.getDecoder().decode(installation.getPublicKey()));
        String pem = "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----";
        return new LicenseRequest(
                installation.getId().toString(),
                installation.getReferencia(),
                pem);
    }

    private Installation currentInstallation() {
        return instalacionRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("La instalacion no esta inicializada"));
    }

    private boolean isDemoCompany() {
        return !empresaRepository.findByTaxId(Company.DEMO_TAX_ID).isEmpty();
    }

    public record InstallationStatus(
            java.util.UUID id,
            String reference,
            Instant createdAt,
            Instant demoUntil,
            OperationalMode mode,
            String activeLicenseReference,
            boolean organizationProvisioned) {
    }

    public record LicenseRequest(String id, String reference, String publicKey) {
    }
}
