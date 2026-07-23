package com.tpverp.saas.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public record CreateSalesDocumentRequest(
        UUID storeId,
        @NotBlank String documentNumber,
        String customerCode,
        @NotBlank String total,
        @NotBlank String currency,
        String status,
        @NotNull Instant issuedAt) {
}
