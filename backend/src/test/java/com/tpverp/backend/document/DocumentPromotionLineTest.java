package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

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
