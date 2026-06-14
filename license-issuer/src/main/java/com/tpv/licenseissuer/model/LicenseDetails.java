package com.tpv.licenseissuer.model;

import java.time.LocalDate;
import java.util.Objects;

public record LicenseDetails(
        String taxId,
        TaxpayerType taxpayerType,
        String company,
        String store,
        LocalDate validFrom,
        LocalDate validUntil,
        int maxWindows,
        int maxPda,
        TaxRegime impuestos) {

    public LicenseDetails {
        taxId = normalizeTaxId(taxId);
        Objects.requireNonNull(taxpayerType, "taxpayerType is required");
        company = requireText(company, "company");
        store = requireText(store, "store");
        Objects.requireNonNull(validFrom, "validFrom is required");
        Objects.requireNonNull(validUntil, "validUntil is required");
        Objects.requireNonNull(impuestos, "impuestos is required");
        if (!validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("validUntil must be after validFrom");
        }
        if (maxWindows < 1 || maxPda < 0) {
            throw new IllegalArgumentException("Windows quota must be positive and PDA quota cannot be negative");
        }
    }

    private static String normalizeTaxId(String value) {
        String normalized = requireText(value, "taxId")
                .replace(" ", "")
                .replace("-", "")
                .toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-Z0-9]{9}")) {
            throw new IllegalArgumentException("taxId is invalid");
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
