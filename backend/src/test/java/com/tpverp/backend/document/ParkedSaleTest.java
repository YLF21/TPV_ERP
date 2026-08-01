package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ParkedSaleTest {

    @Test
    void opensLegacySnapshotWithoutOverrideFlagsAsNonOverride() {
        var parked = parkedSale("Instantanea antigua");
        var snapshot = parked.documentSnapshot();
        var storedLine = storedLine(snapshot);
        storedLine.remove("temporaryNameOverride");
        storedLine.remove("temporaryPriceOverride");

        var restored = ParkedSale.documentCommand(snapshot).lineas().get(0);

        assertThat(restored.temporaryNameOverride()).isFalse();
        assertThat(restored.temporaryPriceOverride()).isFalse();
    }

    @Test
    void rejectsCorruptOverrideFlagsInsteadOfSilentlyLosingIntent() {
        var snapshot = parkedSale("Instantanea corrupta").documentSnapshot();
        storedLine(snapshot).put("temporaryPriceOverride", "true");

        assertThatThrownBy(() -> ParkedSale.documentCommand(snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("parked_sale_snapshot_invalid_temporaryPriceOverride");
    }

    private static ParkedSale parkedSale(String comment) {
        return new ParkedSale(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.parse("2026-06-17T10:00:00Z"),
                new DocumentCommand(
                        UUID.randomUUID(),
                        CommercialDocumentType.TICKET,
                        LocalDate.of(2026, 6, 17),
                        UUID.randomUUID(),
                        null,
                        null,
                        BigDecimal.ZERO,
                        false,
                        List.of(new DocumentLineCommand(
                                UUID.randomUUID(), BigDecimal.ONE, "P-1", "Producto",
                                "VENTA", new BigDecimal("10.00"), BigDecimal.ZERO,
                                true, "IVA", new BigDecimal("21")))),
                comment);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> storedLine(Map<String, Object> snapshot) {
        return (Map<String, Object>) ((List<?>) snapshot.get("lineas")).get(0);
    }
}
