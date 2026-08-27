package com.tpverp.backend.licensing;

import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SaasLicenseIdentityResolver {

    private final LicenseRepository licenses;

    public SaasLicenseIdentityResolver(LicenseRepository licenses) {
        this.licenses = licenses;
    }

    @Transactional(readOnly = true)
    public SaasIdentity resolve(UUID localCompanyId, UUID localStoreId) {
        Objects.requireNonNull(localCompanyId, "localCompanyId");
        if (localStoreId == null) {
            throw new IllegalStateException("El evento sync no identifica la tienda local");
        }
        License license = licenses.findFirstByTienda_IdAndActivaTrueOrderByValidaDesdeDesc(localStoreId)
                .orElseThrow(() -> new IllegalStateException(
                        "La tienda no tiene una licencia SaaS activa"));
        if (!localCompanyId.equals(license.getLocalCompanyId())) {
            throw new IllegalStateException("La empresa del evento sync no coincide con su licencia");
        }
        UUID companyId = license.getSaasCompanyId();
        UUID storeId = license.getSaasStoreId();
        if (companyId == null || storeId == null) {
            throw new IllegalStateException("La licencia activa no contiene la identidad SaaS");
        }
        return new SaasIdentity(companyId, storeId);
    }

    public record SaasIdentity(UUID companyId, UUID storeId) {
    }
}
