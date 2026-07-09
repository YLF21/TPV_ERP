package com.tpverp.backend.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PromotionDomainTest {

    @Test
    void usedPromotionCannotBeChangedDirectly() {
        var promotion = Promotion.draft(
                UUID.randomUUID(),
                "3x2 Agua",
                PromotionType.BUY_X_PAY_Y,
                LocalDate.now());

        promotion.markUsed();

        assertThatThrownBy(() -> promotion.rename("2x1 Agua"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("promotion.used_requires_new_version");
    }

    @Test
    void cancelledCouponCanReactivateOnlyIfNotExpired() {
        var userId = UUID.randomUUID();
        var coupon = PromotionalCoupon.amount(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "hash-123",
                "0123",
                new BigDecimal("5.00"),
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31));

        coupon.cancel(userId, "error de emision", Instant.parse("2026-07-05T10:00:00Z"));
        coupon.reactivate(
                userId,
                "corregido",
                LocalDate.of(2026, 7, 20),
                Instant.parse("2026-07-20T10:00:00Z"));

        assertThat(coupon.status()).isEqualTo(PromotionalCouponStatus.ACTIVE);
    }

    @Test
    void usedCouponCannotBeCancelledOrReactivated() {
        var userId = UUID.randomUUID();
        var coupon = PromotionalCoupon.amount(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "hash-456",
                "0456",
                new BigDecimal("10.00"),
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31));

        coupon.use(UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-07-10T10:00:00Z"));

        assertThatThrownBy(() -> coupon.cancel(userId, "anulacion", Instant.parse("2026-07-11T10:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("coupon.used_cannot_cancel");
        assertThatThrownBy(() -> coupon.reactivate(
                userId,
                "reactivar",
                LocalDate.of(2026, 7, 11),
                Instant.parse("2026-07-11T10:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("coupon.only_cancelled_can_reactivate");
    }

    @Test
    void expiredCancelledCouponCannotReactivate() {
        var userId = UUID.randomUUID();
        var coupon = PromotionalCoupon.amount(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "hash-789",
                "0789",
                new BigDecimal("10.00"),
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31));

        coupon.cancel(userId, "error de emision", Instant.parse("2026-07-05T10:00:00Z"));

        assertThatThrownBy(() -> coupon.reactivate(
                userId,
                "fuera de plazo",
                LocalDate.of(2026, 8, 1),
                Instant.parse("2026-08-01T10:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("coupon.expired_cannot_reactivate");
    }

    @Test
    void promotionNameRejectsDatabaseInvalidLength() {
        assertThatThrownBy(() -> Promotion.draft(
                UUID.randomUUID(),
                "A".repeat(161),
                PromotionType.BUY_X_PAY_Y,
                LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nombre");
    }

    @Test
    void buyXPayYPromotionRequiresQuantitiesBeforeActivation() {
        var promotion = Promotion.draft(
                UUID.randomUUID(),
                "3x2 Agua",
                PromotionType.BUY_X_PAY_Y,
                LocalDate.now());

        assertThatThrownBy(promotion::activate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("compraCantidad");
    }

    @Test
    void secondUnitPercentPromotionRequiresPositivePercentBeforeActivation() {
        var promotion = Promotion.draft(
                UUID.randomUUID(),
                "Segunda unidad",
                PromotionType.SECOND_UNIT_PERCENT,
                LocalDate.now());

        assertThatThrownBy(promotion::activate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("descuentoPorcentaje");
    }

    @Test
    void secondUnitPercentPromotionRejectsPercentAboveOneHundredBeforeActivation() {
        var promotion = Promotion.draft(
                UUID.randomUUID(),
                "Segunda unidad",
                PromotionType.SECOND_UNIT_PERCENT,
                LocalDate.now());
        ReflectionTestUtils.setField(promotion, "descuentoPorcentaje", new BigDecimal("101.00"));

        assertThatThrownBy(promotion::activate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("descuentoPorcentaje");
    }

    @Test
    void couponCodeHashRejectsDatabaseInvalidLength() {
        assertThatThrownBy(() -> PromotionalCoupon.amount(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "H".repeat(129),
                "0123",
                new BigDecimal("10.00"),
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("codigoHash");
    }
}
