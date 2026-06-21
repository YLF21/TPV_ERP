package com.tpverp.backend.verifactu;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FiscalCorrectionRequest(
        @NotBlank @Size(max = 500) String reason,
        @Size(max = 9) String recipientTaxId,
        @Size(max = 120) String recipientName,
        @Size(max = 500) String operationDescription) {
}
