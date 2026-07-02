package com.tpverp.backend.excel;

import java.util.List;
import java.util.UUID;

public record ProductImportPreviewRow(
        int rowNumber,
        Status status,
        UUID productId,
        List<String> errors,
        List<ProductChange> changes) {

    public enum Status {
        NEW_PRODUCT,
        UPDATE_PRODUCT,
        PRODUCT_ONLY,
        SKIPPED,
        ERROR
    }

    public record ProductChange(String campo, String valorActual, String valorExcel) {
    }
}
