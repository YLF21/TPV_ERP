package com.tpverp.backend.verifactu;

import java.util.UUID;

/** Read-only fiscal operational projection, scoped to the local installation. */
public interface FiscalOperationalStatusRepository {

    FiscalOperationalStatusSnapshot findForScope(UUID companyId, UUID installationId);

    FiscalOperationalStatusSnapshot findGlobal();
}
