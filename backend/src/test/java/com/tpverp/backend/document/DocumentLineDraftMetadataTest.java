package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentLineDraftMetadataTest {

    @Test
    void preservesTemporaryNameAndPriceFlagsWhenRebuildingADraftLine() {
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(),
                CommercialDocumentType.FACTURA_VENTA, LocalDate.of(2026, 8, 12),
                UUID.randomUUID(), BigDecimal.ZERO);
        var command = new DocumentLineCommand(
                UUID.randomUUID(), BigDecimal.ONE, "P-1", "Nombre temporal",
                null, new BigDecimal("12.50"), BigDecimal.ZERO,
                true, "IVA", new BigDecimal("21"), DocumentLineType.PRODUCT,
                null, null, null, List.of(), true, true);

        var restored = DocumentLineCommand.from(command.toEntity(document));

        assertThat(restored.temporaryNameOverride()).isTrue();
        assertThat(restored.temporaryPriceOverride()).isTrue();
    }
}
