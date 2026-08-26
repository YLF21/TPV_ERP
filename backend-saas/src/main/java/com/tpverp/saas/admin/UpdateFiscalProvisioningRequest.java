package com.tpverp.saas.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record UpdateFiscalProvisioningRequest(
        @NotNull Map<String, String> companyAddress,
        @NotEmpty List<@Valid StoreProvisioning> stores) {

    public record StoreProvisioning(
            @NotNull UUID storeId,
            @NotNull Map<String, String> storeAddress,
            @NotNull String timeZoneId) {
    }
}
