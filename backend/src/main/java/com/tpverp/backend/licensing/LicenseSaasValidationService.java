package com.tpverp.backend.licensing;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class LicenseSaasValidationService {

    private final InstallationRepository installations;
    private final StoreRepository stores;
    private final LicenseRepository licenses;
    private final LicenseSaasValidationClient client;
    private final Clock clock;
    private final CurrentOrganization organization;
    private final LicenseSaasCacheAuthenticator cacheAuthenticator;

    public LicenseSaasValidationService(
            InstallationRepository installations,
            StoreRepository stores,
            LicenseRepository licenses,
            LicenseSaasValidationClient client,
            Clock clock,
            LicenseSaasCacheAuthenticator cacheAuthenticator) {
        this(installations, stores, licenses, client, clock, null, cacheAuthenticator);
    }

    public LicenseSaasValidationService(
            InstallationRepository installations,
            StoreRepository stores,
            LicenseRepository licenses,
            LicenseSaasValidationClient client,
            Clock clock,
            CurrentOrganization organization,
            LicenseSaasCacheAuthenticator cacheAuthenticator) {
        this.installations = installations;
        this.stores = stores;
        this.licenses = licenses;
        this.client = client;
        this.clock = clock;
        this.organization = organization;
        this.cacheAuthenticator = cacheAuthenticator;
    }

    @Transactional
    public LicenseSaasValidationResponse validateActiveLicense() {
        Installation installation = currentInstallation();
        Store store = currentStore();
        return licenses.findActiveForSaasValidationForUpdate(store.getId(), installation.getId())
                .map(license -> validate(installation, store, license))
                .orElse(null);
    }

    /** Returns all active licenses actually linked to SaaS. */
    @Transactional(readOnly = true)
    public List<UUID> activeSaasLicenseIds() {
        return licenses.findByActivaTrueOrderByValidaDesdeDesc().stream()
                .filter(this::isSaasRefreshCandidate)
                .map(License::getId)
                .distinct()
                .toList();
    }

    /**
     * Refreshes one explicitly selected license. Scheduler callers invoke this
     * once per id so a failure cannot roll back another store's validation.
     */
    @Transactional
    public LicenseSaasValidationResponse validateLicense(UUID licenseId) {
        License license = licenses.findByIdForSaasValidationForUpdate(licenseId)
                .orElseThrow(() -> new IllegalStateException("Licencia SaaS local no encontrada"));
        if (!isSaasRefreshCandidate(license)) {
            return null;
        }
        Installation installation = installations.findById(license.getInstalacionId())
                .orElseThrow(() -> new IllegalStateException("Instalacion de la licencia no encontrada"));
        Store store = stores.findWithCompanyById(license.getTiendaId())
                .orElseThrow(() -> new IllegalStateException("Tienda de la licencia no encontrada"));
        return validate(installation, store, license);
    }

    private boolean isSaasRefreshCandidate(License license) {
        return license.isActiva()
                && license.getSaasCompanyId() != null
                && license.getSaasStoreId() != null
                && (license.getSaasLicenseVersion() != null
                        || ((license.getFormatVersion() == 4 || license.getFormatVersion() == 5)
                                && license.isSaasLinked()));
    }

    private LicenseSaasValidationResponse validate(Installation installation, Store store, License license) {
        boolean legacyV5 = license.getFormatVersion() == 5;
        // Format 5 is accepted only after the authenticator proves its legacy
        // MAC, including the bounded PostgreSQL rounding variants.
        cacheAuthenticator.requireRefreshable(license);
        LicenseSaasValidationResponse response = client.validate(new LicenseSaasValidationRequest(
                installation.getId(),
                installation.getReferencia(),
                store.getId(),
                license.getReferencia(),
                license.getHash()));
        if (legacyV5) {
            validateLegacyV5AuthoritativeIdentity(license, response);
        }
        Instant now = Instant.now(clock);
        if (canApplyVerifactuPolicy(license, store, response, now)) {
            license.applyVerifactuPolicy(
                    response.verifactuActivationDate(),
                    response.verifactuPolicyVersion(),
                    response.verifactuPolicyUpdatedAt());
        }
        if (response.commercialProfile() != null) {
            license.updateCommercialProfile(response.commercialProfile());
        }
        license.applySaasLicenseSnapshot(
                now,
                response.status(),
                response.validUntil(),
                response.maxWindows(),
                response.maxPda(),
                response.licenseVersion());
        cacheAuthenticator.seal(license);
        licenses.save(license);
        return response;
    }

    /**
     * PostgreSQL rounds timestamp values to microseconds. A format-5 cache was
     * sealed before that round-trip and can therefore fail its legacy MAC even
     * when the installation is genuine. In that case the SaaS response is the
     * trust anchor: only a response bound to the same central licence/store is
     * allowed to upgrade the local snapshot to format 6.
     */
    private void validateLegacyV5AuthoritativeIdentity(
            License license, LicenseSaasValidationResponse response) {
        if (response == null
                || response.saasCompanyId() == null
                || response.saasStoreId() == null
                || response.licenseReference() == null
                || !response.saasCompanyId().equals(license.getSaasCompanyId())
                || !response.saasStoreId().equals(license.getSaasStoreId())
                || !response.licenseReference().equals(license.getReferencia())) {
            throw new com.tpverp.backend.licensing.application.LicenseValidationException(
                    "La respuesta SaaS no coincide con la identidad central de la licencia legacy; "
                            + "use el re-enlace asistido");
        }
    }

    private boolean canApplyVerifactuPolicy(
            License license,
            Store store,
            LicenseSaasValidationResponse response,
            Instant now) {
        if (response.verifactuActivationDate() == null
                || response.verifactuPolicyUpdatedAt() == null) {
            return false;
        }
        LocalDate currentDate = license.getVerifactuActivationDate();
        if (currentDate == null) {
            return true;
        }
        LocalDate today = now.atZone(ZoneId.of(store.getTimezone())).toLocalDate();
        return today.isBefore(currentDate)
                || !response.verifactuActivationDate().isAfter(currentDate);
    }

    private Installation currentInstallation() {
        return installations.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("La instalacion no esta inicializada"));
    }

    private Store currentStore() {
        if (organization != null) {
            return organization.currentStore();
        }
        return stores.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("La tienda no esta inicializada"));
    }
}
