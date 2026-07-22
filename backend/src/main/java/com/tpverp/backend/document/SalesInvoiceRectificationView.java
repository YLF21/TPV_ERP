package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record SalesInvoiceRectificationView(
        DocumentView document,
        SalesInvoiceRectificationSourceView original,
        SalesInvoiceRectificationFiscalType fiscalType,
        SalesInvoiceRectificationMethod method,
        SalesInvoiceRectificationReason reason,
        String detail,
        boolean affectsStock,
        List<LineView> lines) {

    public record LineView(
            UUID id,
            UUID originalLineId,
            DocumentLineType type,
            String code,
            String name,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal base,
            BigDecimal tax,
            BigDecimal total) {
    }
}
