package com.tpverp.saas.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateInvoiceFiscalRequest(
        @NotBlank String fiscalStatus,
        String taxBase,
        String taxRate,
        String taxAmount,
        @Size(max = 500) String reason,
        @Size(max = 500) String legalBasis,
        @Size(max = 500) String evidenceReference) {
}
