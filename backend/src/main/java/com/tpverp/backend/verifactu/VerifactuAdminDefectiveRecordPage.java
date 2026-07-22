package com.tpverp.backend.verifactu;

import java.util.List;

public record VerifactuAdminDefectiveRecordPage(
        List<VerifactuAdminDefectiveRecordView> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public VerifactuAdminDefectiveRecordPage {
        items = List.copyOf(items);
    }
}
