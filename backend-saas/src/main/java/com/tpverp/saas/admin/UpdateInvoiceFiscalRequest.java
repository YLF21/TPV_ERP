package com.tpverp.saas.admin;

import jakarta.validation.constraints.NotBlank;

public record UpdateInvoiceFiscalRequest(
        @NotBlank String fiscalStatus,
        String taxBase,
        String taxRate,
        String taxAmount) {
}
