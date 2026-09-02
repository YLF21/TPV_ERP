package com.tpverp.saas.admin;

import java.util.UUID;

public record InvoiceFiscalDetailResponse(
        UUID invoiceId,
        UUID companyId,
        String number,
        String series,
        int fiscalYear,
        String taxRegime,
        String fiscalStatus,
        String taxBase,
        String taxRate,
        String taxAmount,
        String total,
        String currency) {
}
