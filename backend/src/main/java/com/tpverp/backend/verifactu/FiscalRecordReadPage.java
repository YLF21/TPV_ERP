package com.tpverp.backend.verifactu;

import java.util.List;

/** Paged, sanitized fiscal-record result for APP GESTION. */
public record FiscalRecordReadPage(
        List<FiscalRecordReadView> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public FiscalRecordReadPage {
        items = List.copyOf(items);
    }
}
