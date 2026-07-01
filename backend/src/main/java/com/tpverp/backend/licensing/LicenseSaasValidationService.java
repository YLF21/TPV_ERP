package com.tpverp.backend.licensing;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.transaction.annotation.Transactional;

public class LicenseSaasValidationService {

    private final InstallationRepository installations;
    private final StoreRepository stores;
    private final LicenseRepository licenses;
    private final LicenseSaasValidationClient client;
    private final Clock clock;

    public LicenseSaasValidationService(
            InstallationRepository installations,
            StoreRepository stores,
            LicenseRepository licenses,
            LicenseSaasValidationClient client,
            Clock clock) {
        this.installations = installations;
        this.stores = stores;
        this.licenses = licenses;
        this.client = client;
        this.clock = clock;
    }

    @Transactional
    public void validateActiveLicense() {
        Installation installation = currentInstallation();
        Store store = currentStore();
        licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(store.getId(), installation.getId())
                .ifPresent(license -> validate(installation, store, license));
    }

    private void validate(Installation installation, Store store, License license) {
        LicenseSaasValidationResponse response = client.validate(new LicenseSaasValidationRequest(
                installation.getId(),
                installation.getReferencia(),
                store.getId(),
                license.getReferencia(),
                license.getHash()));
        Instant now = Instant.now(clock);
        if (response.status() == LicenseSaasStatus.BLOQUEADA_MANUAL) {
            license.markSaasBlocked(now);
        } else {
            license.markSaasValidated(now, response.validUntil());
        }
        licenses.save(license);
    }

    private Installation currentInstallation() {
        return installations.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("La instalacion no esta inicializada"));
    }

    private Store currentStore() {
        return stores.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("La tienda no esta inicializada"));
    }
}
