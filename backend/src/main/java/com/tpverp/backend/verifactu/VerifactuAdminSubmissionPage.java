package com.tpverp.backend.verifactu;

import java.util.List;

public record VerifactuAdminSubmissionPage(
        List<VerifactuAdminSubmissionView> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public VerifactuAdminSubmissionPage {
        items = List.copyOf(items);
    }
}
