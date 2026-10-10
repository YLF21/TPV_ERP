package com.tpverp.backend.licensing;

import com.tpverp.backend.organization.Store;
import java.util.UUID;

/** Copies presentation metadata only from the authoritative identity already linked to this store. */
final class LicenseSaasStorePresentation {
    private LicenseSaasStorePresentation() { }

    static void rememberCode(Store store, License license, UUID companyId, UUID storeId,
            String licenseReference, String internalCode) {
        if (companyId != null && storeId != null && licenseReference != null
                && store.getId().equals(license.getTiendaId())
                && companyId.equals(license.getSaasCompanyId())
                && storeId.equals(license.getSaasStoreId())
                && licenseReference.equals(license.getReferencia())) {
            store.rememberSaasInternalCode(internalCode);
        }
    }
}
