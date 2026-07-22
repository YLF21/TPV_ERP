package com.tpverp.backend.verifactu;

import java.util.List;

public record VerifactuAdminAttemptPage(
        List<VerifactuAdminAttemptView> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public VerifactuAdminAttemptPage {
        items = List.copyOf(items);
    }
}
