package com.tpv.licenseissuer.model;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class LicenseDetailsTest {
    @Test
    void rejectsAnEndDateBeforeTheStartDate() {
        assertThrows(IllegalArgumentException.class, () -> new LicenseDetails(
                "B12345678", TaxpayerType.SOCIEDAD, "Example SL", "Main Store",
                LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 9),
                3, 10, TaxRegime.IVA));
    }

    @Test
    void rejectsNonPositiveQuotas() {
        assertThrows(IllegalArgumentException.class, () -> new LicenseDetails(
                "B12345678", TaxpayerType.SOCIEDAD, "Example SL", "Main Store",
                LocalDate.of(2026, 6, 8), LocalDate.of(2027, 6, 8),
                0, 10, TaxRegime.IVA));
    }

    @Test
    void acceptsAZeroPdaQuota() {
        new LicenseDetails(
                "B12345678", TaxpayerType.AUTONOMO, "Example SL", "Main Store",
                LocalDate.of(2026, 6, 8), LocalDate.of(2027, 6, 8),
                1, 0, TaxRegime.IGIC);
    }

    @Test
    void rejectsBlankCompanyData() {
        assertThrows(IllegalArgumentException.class, () -> new LicenseDetails(
                "B12345678", TaxpayerType.SOCIEDAD, " ", "Main Store",
                LocalDate.of(2026, 6, 8), LocalDate.of(2027, 6, 8),
                3, 10, TaxRegime.IVA));
    }

    @Test
    void rejectsAMissingTaxRegime() {
        assertThrows(NullPointerException.class, () -> new LicenseDetails(
                "B12345678", TaxpayerType.SOCIEDAD, "Example SL", "Main Store",
                LocalDate.of(2026, 6, 8), LocalDate.of(2027, 6, 8),
                3, 10, null));
    }

    @Test
    void rejectsBlankTaxId() {
        assertThrows(IllegalArgumentException.class, () -> new LicenseDetails(
                " ", TaxpayerType.SOCIEDAD, "Example SL", "Main Store",
                LocalDate.of(2026, 6, 8), LocalDate.of(2027, 6, 8),
                3, 10, TaxRegime.IVA));
    }

    @Test
    void rejectsMissingTaxpayerType() {
        assertThrows(NullPointerException.class, () -> new LicenseDetails(
                "B12345678", null, "Example SL", "Main Store",
                LocalDate.of(2026, 6, 8), LocalDate.of(2027, 6, 8),
                3, 10, TaxRegime.IVA));
    }
}
