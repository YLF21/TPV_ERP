package com.tpverp.saas.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import java.util.Map;

public record CustomerIdentityReservationRequest(
        @NotNull UUID companyId,
        @NotNull UUID storeId,
        @NotNull UUID operationId,
        @NotNull UUID localCustomerId,
        UUID expectedCustomerId,
        Long expectedRevision,
        @NotBlank @Size(max = 20) String documentType,
        @NotBlank @Size(max = 64) String documentNumber,
        @NotNull Map<String, Object> profile) {
}
