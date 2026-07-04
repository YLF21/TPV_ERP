package com.tpverp.backend.licensing;

import java.util.UUID;

public record LicenseSaasLinkResult(
        LicenseSaasLinkResponse license,
        UUID localCompanyId,
        UUID localStoreId,
        UUID serverTerminalId) {
}
