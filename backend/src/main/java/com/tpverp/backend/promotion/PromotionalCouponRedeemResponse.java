package com.tpverp.backend.promotion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PromotionalCouponRedeemResponse(
        UUID redeemedDocumentId,
        BigDecimal redeemedAmount,
        CouponRejectReason rejectionReason,
        ReplacementCoupon replacementCoupon) {

    static PromotionalCouponRedeemResponse from(PromotionalCouponService.RedemptionResult result) {
        return new PromotionalCouponRedeemResponse(
                result.redeemedDocumentId(),
                result.redeemedAmount(),
                result.rejectionReason(),
                result.replacementCoupon()
                        .map(ReplacementCoupon::from)
                        .orElse(null));
    }

    public record ReplacementCoupon(
            UUID couponId,
            String code,
            String codeLast4,
            LocalDate validFrom,
            LocalDate validUntil) {

        static ReplacementCoupon from(PromotionalCouponService.CreationResult result) {
            return new ReplacementCoupon(
                    result.couponId(),
                    result.code(),
                    result.codeLast4(),
                    result.validFrom(),
                    result.validUntil());
        }
    }
}
