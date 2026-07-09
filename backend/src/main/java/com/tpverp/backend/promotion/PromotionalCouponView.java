package com.tpverp.backend.promotion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PromotionalCouponView(
        UUID id,
        String codeLast4,
        PromotionalCouponStatus status,
        LocalDate validFrom,
        LocalDate validUntil,
        UUID promotionId,
        UUID generatedStoreId,
        UUID redeemedStoreId,
        UUID generatedDocumentId,
        UUID redeemedDocumentId,
        UUID customerId,
        UUID memberId,
        PromotionalCouponBenefitType benefitType,
        BigDecimal amount,
        BigDecimal percent,
        BigDecimal maximumDiscount,
        BigDecimal minimumAmount) {

    static PromotionalCouponView from(PromotionalCouponService.CouponView coupon) {
        return new PromotionalCouponView(
                coupon.id(),
                coupon.codeLast4(),
                coupon.status(),
                coupon.validFrom(),
                coupon.validUntil(),
                coupon.promotionId(),
                coupon.generatedStoreId(),
                coupon.redeemedStoreId(),
                coupon.generatedDocumentId(),
                coupon.redeemedDocumentId(),
                coupon.customerId(),
                coupon.memberId(),
                coupon.benefitType(),
                coupon.amount(),
                coupon.percent(),
                coupon.maximumDiscount(),
                coupon.minimumAmount());
    }
}
