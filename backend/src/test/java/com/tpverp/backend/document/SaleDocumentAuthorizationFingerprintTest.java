package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SaleDocumentAuthorizationFingerprintTest {

    private final SaleDocumentAuthorizationFingerprint fingerprints =
            new SaleDocumentAuthorizationFingerprint();

    @Test
    void isStableAndChangesWhenDraftContentChanges() {
        var document = document();

        var original = fingerprints.fingerprint(document);
        assertThat(fingerprints.fingerprint(document)).isEqualTo(original);

        document.setInternalComment("Cambio posterior a la autorización");

        assertThat(fingerprints.fingerprint(document))
                .hasSize(64)
                .isNotEqualTo(original);
    }

    @Test
    void ruleActivationIsIncludedInAuthorizationWithoutChangingLegacyActiveFingerprint() throws Exception {
        var document = document();
        var rule = com.tpverp.backend.catalog.QuantityPricing.fromJson(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree("{\"mode\":\"PACK\",\"tiers\":[{\"quantity\":3,\"price\":5.99}]}"));
        var line = document.getLineas().getFirst();
        line.assignQuantityPriceSnapshot(new QuantityPriceSnapshot(rule, new BigDecimal("2.50")));
        var historical = fingerprints.fingerprint(document);
        line.assignQuantityPriceSnapshot(new QuantityPriceSnapshot(
                new com.tpverp.backend.catalog.QuantityPricing(rule.mode(), rule.tiers(), false), new BigDecimal("2.50")));
        assertThat(fingerprints.fingerprint(document)).isNotEqualTo(historical);
        line.assignQuantityPriceSnapshot(new QuantityPriceSnapshot(
                new com.tpverp.backend.catalog.QuantityPricing(rule.mode(), rule.tiers(), true), new BigDecimal("2.50")));
        assertThat(fingerprints.fingerprint(document)).isEqualTo(historical);
    }

    private static CommercialDocument document() {
        var document = new CommercialDocument(
                UUID.randomUUID(),
                UUID.randomUUID(),
                CommercialDocumentType.ALBARAN_VENTA,
                LocalDate.of(2026, 7, 31),
                UUID.randomUUID(),
                new BigDecimal("5.00"));
        document.setParties(UUID.randomUUID(), null, null);
        document.addLine(new DocumentLine(
                document,
                UUID.randomUUID(),
                1,
                new BigDecimal("2.000"),
                "P1",
                "Producto",
                "VENTA",
                new BigDecimal("10.00"),
                new BigDecimal("3.00"),
                true,
                "IVA",
                new BigDecimal("21.00")));
        return document;
    }
}
