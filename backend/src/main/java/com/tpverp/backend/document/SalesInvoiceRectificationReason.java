package com.tpverp.backend.document;

public enum SalesInvoiceRectificationReason {
    GOODS_RETURN(SalesInvoiceRectificationFiscalType.R1, true),
    POST_SALE_DISCOUNT(SalesInvoiceRectificationFiscalType.R1, false),
    POST_SALE_PRICE_CHANGE(SalesInvoiceRectificationFiscalType.R1, false),
    OPERATION_CANCELLATION(SalesInvoiceRectificationFiscalType.R1, true),
    LEGAL_OR_TAX_ERROR(SalesInvoiceRectificationFiscalType.R1, false),
    OTHER(SalesInvoiceRectificationFiscalType.R4, false);

    private final SalesInvoiceRectificationFiscalType fiscalType;
    private final boolean affectsStock;

    SalesInvoiceRectificationReason(
            SalesInvoiceRectificationFiscalType fiscalType,
            boolean affectsStock) {
        this.fiscalType = fiscalType;
        this.affectsStock = affectsStock;
    }

    public SalesInvoiceRectificationFiscalType fiscalType() {
        return fiscalType;
    }

    public boolean affectsStock() {
        return affectsStock;
    }
}
