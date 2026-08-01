package com.tpverp.backend.document;

public record ParkedSaleOpened(
        DocumentCommand document,
        String comment,
        SalePrintMode printMode) {

    public ParkedSaleOpened(DocumentCommand document, String comment) {
        this(document, comment, SalePrintMode.DEFAULT);
    }
}
