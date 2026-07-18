package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductBulkEditTest {

    @Test
    void nextVersionKeepsSeriesAndCopiesComments() {
        UUID creatorId = UUID.randomUUID();
        UUID nextCreatorId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Instant originalTime = Instant.parse("2026-07-11T09:00:00Z");
        ProductBulkEdit original = new ProductBulkEdit(
                UUID.randomUUID(),
                "20260711001",
                "Revision precios",
                List.of(row("row-1", productId)),
                creatorId,
                originalTime);
        original.addComment("Revisar margen", creatorId, originalTime.plusSeconds(60));
        original.apply(original.getContenido(), creatorId, originalTime.plusSeconds(120));

        ProductBulkEdit next = original.nextVersion(
                "20260711002",
                2,
                original.getNombre(),
                List.of(row("row-1", productId)),
                nextCreatorId,
                originalTime.plusSeconds(180));

        assertThat(next.getId()).isNotEqualTo(original.getId());
        assertThat(next.getSerieId()).isEqualTo(original.getSerieId());
        assertThat(next.getNumeroVersion()).isEqualTo(2);
        assertThat(next.getVersionAnteriorId()).isEqualTo(original.getId());
        assertThat(next.getEstado()).isEqualTo(ProductBulkEditStatus.PENDING);
        assertThat(next.getCreadoPor()).isEqualTo(nextCreatorId);
        assertThat(next.getComentarios()).singleElement().satisfies(comment -> {
            assertThat(comment.getTexto()).isEqualTo("Revisar margen");
            assertThat(comment.getUsuarioId()).isEqualTo(creatorId);
            assertThat(comment.getCreadoEn()).isEqualTo(originalTime.plusSeconds(60));
        });
    }

    @Test
    void appliedVersionCannotBeAppliedAgain() {
        UUID userId = UUID.randomUUID();
        ProductBulkEdit edit = new ProductBulkEdit(
                UUID.randomUUID(), "20260711001", "Revision", List.of(), userId, Instant.now());
        edit.apply(List.of(), userId, Instant.now());

        assertThatThrownBy(() -> edit.apply(List.of(), userId, Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ya esta aplicada");
    }

    @Test
    void contentRejectsExcessiveRowsAndJsonSize() {
        ProductBulkEditContent.Row row = row("row-1", UUID.randomUUID());

        assertThatThrownBy(() -> ProductBulkEditContent.validateAndCopy(
                java.util.Collections.nCopies(5_001, row)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5000 filas");

        ProductBulkEditContent.Row oversized = new ProductBulkEditContent.Row(
                "row-large", false, "x".repeat(8 * 1024 * 1024),
                row.product(), row.draft(), List.of(), null);
        assertThatThrownBy(() -> ProductBulkEditContent.validateAndCopy(List.of(oversized)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8 MB");
    }

    @Test
    void contentRejectsTooManySuppliersPerRow() {
        ProductBulkEditContent.Row row = row("row-1", UUID.randomUUID());
        ProductBulkEditContent.SupplierData supplier = new ProductBulkEditContent.SupplierData(
                UUID.randomUUID(), null, null, null, null, true,
                null, false, false, null, null, null, null);
        ProductBulkEditContent.Row excessive = new ProductBulkEditContent.Row(
                row.id(), row.selected(), row.query(), row.product(), row.draft(),
                java.util.Collections.nCopies(101, supplier), null);

        assertThatThrownBy(() -> ProductBulkEditContent.validateAndCopy(List.of(excessive)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100 elementos");
    }

    @Test
    void contentRejectsInvalidProductActiveValue() {
        ProductBulkEditContent.Row base = row("row-1", UUID.randomUUID());
        ProductBulkEditContent.Row invalid = new ProductBulkEditContent.Row(
                base.id(), base.selected(), base.query(), base.product().withActive("maybe"),
                base.draft(), base.suppliers(), base.pendingSupplier());

        assertThatThrownBy(() -> ProductBulkEditContent.validateAndCopy(List.of(invalid)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active no es un booleano valido");
    }

    private static ProductBulkEditContent.Row row(String id, UUID productId) {
        ProductBulkEditContent.ProductData product = new ProductBulkEditContent.ProductData(
                productId,
                0L,
                null,
                null,
                "P-1",
                null,
                null,
                "Producto",
                null,
                null,
                "10.00",
                "0.00",
                "12.00",
                null,
                null,
                null,
                null,
                ProductType.UNIT.name(),
                "NORMAL",
                DiscountType.NORMAL.name(),
                UUID.randomUUID().toString(),
                "General",
                null,
                null,
                UUID.randomUUID().toString(),
                "IGIC",
                "true",
                "false",
                null,
                null,
                null,
                "0",
                "0");
        return new ProductBulkEditContent.Row(
                id, false, "P-1", product, ProductBulkEditContent.ProductData.empty(), List.of(), null);
    }
}
