package com.tpverp.saas.admin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record FiscalProvisioningResponse(
        UUID companyId,
        Map<String, String> companyAddress,
        List<StoreProvisioning> stores) {

    public record StoreProvisioning(
            UUID storeId,
            String storeCode,
            String storeName,
            Map<String, String> storeAddress,
            String timeZoneId) {
    }
}
