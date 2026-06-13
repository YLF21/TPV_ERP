package com.tpv.licenseissuer.model;

import java.time.LocalDate;
import java.util.Objects;

public record LicenseDetails(
        String company,
        String store,
        LocalDate validFrom,
        LocalDate validUntil,
        int maxWindows,
        int maxPda) {

    public LicenseDetails {
        company = requireText(company, "company");
        store = requireText(store, "store");
        Objects.requireNonNull(validFrom, "validFrom is required");
        Objects.requireNonNull(validUntil, "validUntil is required");
        if (!validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("validUntil must be after validFrom");
        }
        if (maxWindows < 1 || maxPda < 0) {
            throw new IllegalArgumentException("Windows quota must be positive and PDA quota cannot be negative");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
