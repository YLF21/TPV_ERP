package com.tpverp.backend.verifactu;

import java.util.List;

public record VerifactuAdminSubmissionPage(
        List<VerifactuAdminSubmissionView> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean truncated) {

    /**
     * Compatibility constructor for callers that predate the bounded-window
     * marker. Such pages represent a complete (or unknown) result set.
     */
    public VerifactuAdminSubmissionPage(
            List<VerifactuAdminSubmissionView> items,
            int page,
            int size,
            long totalElements,
            int totalPages) {
        this(items, page, size, totalElements, totalPages, false);
    }

    public VerifactuAdminSubmissionPage {
        items = List.copyOf(items);
    }
}
