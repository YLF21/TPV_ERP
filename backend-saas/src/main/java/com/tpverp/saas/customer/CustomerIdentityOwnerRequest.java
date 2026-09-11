package com.tpverp.saas.customer;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CustomerIdentityOwnerRequest(
        @NotNull UUID companyId, @NotNull UUID storeId, @NotNull UUID localCustomerId) {
}
