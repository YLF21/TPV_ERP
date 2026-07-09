package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentPromotionLineTest {

    @Test
    void promotionalLineDiscountsDocumentWithoutProduct() {
        var document = salesDocument();
        document.addLine(productLine(document, 1, new BigDecimal("3.00")));
        var promotionId = UUID.randomUUID();

        var line = DocumentLine.promotion(
                document, 2, "PROMOCION 3x2 Agua", new BigDecimal("-1.00"),
                true, "IVA", new BigDecimal("21.00"), promotionId, null);
        document.addLine(line);

        assertThat(document.getTotal()).isEqualByComparingTo("2.00");
        assertThat(line.getProductoId()).isNull();
        assertThat(line.getLineType()).isEqualTo(DocumentLineType.PROMOTION);
        assertThat(line.getPromotionId()).isEqualTo(promotionId);
        assertThat(line.getPromotionVersionId()).isNull();
        assertThat(line.getPromotionalCouponId()).isNull();
    }

    @Test
    void couponLineUsesPromotionalCouponType() {
        var document = salesDocument();
        var promotionId = UUID.randomUUID();
        var couponId = UUID.randomUUID();

        var line = DocumentLine.promotion(
                document, 1, "CUPON BIENVENIDA", new BigDecimal("-1.00"),
                true, "IVA", new BigDecimal("21.00"), promotionId, couponId);

        assertThat(line.getLineType()).isEqualTo(DocumentLineType.PROMOTIONAL_COUPON);
        assertThat(line.getProductoId()).isNull();
        assertThat(line.getPromotionId()).isEqualTo(promotionId);
        assertThat(line.getPromotionalCouponId()).isEqualTo(couponId);
    }

    @Test
    void documentRequestConvertsPromotionLineMetadata() {
        var promotionId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var couponId = UUID.randomUUID();
        var request = new DocumentRequest(
                UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 7, 9), null, null, null, BigDecimal.ZERO,
                false,
                java.util.List.of(new DocumentRequest.LineRequest(
                        null, BigDecimal.ONE, "CUPON", "CUPON BIENVENIDA", null,
                        new BigDecimal("-1.00"), BigDecimal.ZERO, true, "IVA",
                        new BigDecimal("21.00"), DocumentLineType.PROMOTIONAL_COUPON,
                        promotionId, versionId, couponId)));

        var line = request.toCommand().lineas().getFirst();

        assertThat(line.productoId()).isNull();
        assertThat(line.lineType()).isEqualTo(DocumentLineType.PROMOTIONAL_COUPON);
        assertThat(line.promotionId()).isEqualTo(promotionId);
        assertThat(line.promotionVersionId()).isEqualTo(versionId);
        assertThat(line.promotionalCouponId()).isEqualTo(couponId);
    }

    @Test
    void documentRequestRejectsProductLineWithoutProductId() {
        var request = new DocumentRequest(
                UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 7, 9), null, null, null, BigDecimal.ZERO,
                false,
                java.util.List.of(new DocumentRequest.LineRequest(
                        null, BigDecimal.ONE, "P-1", "Producto", "VENTA",
                        new BigDecimal("3.00"), BigDecimal.ZERO, true, "IVA",
                        new BigDecimal("21.00"), DocumentLineType.PRODUCT,
                        null, null, null)));

        assertThatThrownBy(request::toCommand)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("productoId");
    }

    private static CommercialDocument salesDocument() {
        return new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 7, 9), UUID.randomUUID(), BigDecimal.ZERO);
    }

    private static DocumentLine productLine(
            CommercialDocument document,
            int position,
            BigDecimal price) {
        return new DocumentLine(
                document, UUID.randomUUID(), position, 1, "AGUA", "Agua",
                "VENTA", price, BigDecimal.ZERO, true, "IVA",
                new BigDecimal("21.00"));
    }
}
