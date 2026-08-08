package com.tpverp.backend.document;

import com.tpverp.backend.promotion.PromotionCustomerSegment;
import com.tpverp.backend.promotion.PromotionalCouponBenefitType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record HistoricalTicketReplayMetadata(
        UUID sourceTicketId,
        String sourceTicketNumber,
        String sourceFingerprint,
        Integer historicalLineCount,
        BigDecimal historicalTotal,
        BigDecimal currentPendingBeforeCoupon,
        List<ManualLineDiscount> currentManualLineDiscounts,
        List<GeneratedCoupon> currentGeneratedCoupons,
        List<HistoricalLoyaltyLine> historicalLoyaltyLines) {

    public HistoricalTicketReplayMetadata(
            UUID sourceTicketId,
            String sourceTicketNumber,
            String sourceFingerprint) {
        this(sourceTicketId, sourceTicketNumber, sourceFingerprint,
                null, null, null, List.of(), List.of(), List.of());
    }

    public HistoricalTicketReplayMetadata(
            UUID sourceTicketId,
            String sourceTicketNumber,
            String sourceFingerprint,
            Integer historicalLineCount,
            BigDecimal historicalTotal,
            BigDecimal currentPendingBeforeCoupon,
            List<ManualLineDiscount> currentManualLineDiscounts) {
        this(sourceTicketId, sourceTicketNumber, sourceFingerprint,
                historicalLineCount, historicalTotal, currentPendingBeforeCoupon,
                currentManualLineDiscounts, List.of(), List.of());
    }

    public HistoricalTicketReplayMetadata(
            UUID sourceTicketId,
            String sourceTicketNumber,
            String sourceFingerprint,
            Integer historicalLineCount,
            BigDecimal historicalTotal,
            BigDecimal currentPendingBeforeCoupon,
            List<ManualLineDiscount> currentManualLineDiscounts,
            List<GeneratedCoupon> currentGeneratedCoupons) {
        this(sourceTicketId, sourceTicketNumber, sourceFingerprint,
                historicalLineCount, historicalTotal, currentPendingBeforeCoupon,
                currentManualLineDiscounts, currentGeneratedCoupons, List.of());
    }

    public HistoricalTicketReplayMetadata {
        Objects.requireNonNull(sourceTicketId, "sourceTicketId");
        if (sourceTicketNumber == null || sourceTicketNumber.isBlank()) {
            throw new IllegalArgumentException("sourceTicketNumber es obligatorio");
        }
        if (sourceFingerprint == null || sourceFingerprint.isBlank()) {
            throw new IllegalArgumentException("sourceFingerprint es obligatorio");
        }
        if (historicalLineCount != null && historicalLineCount < 0) {
            throw new IllegalArgumentException("historicalLineCount no puede ser negativo");
        }
        if (historicalTotal != null) {
            historicalTotal = Money.euros(historicalTotal);
        }
        if (currentPendingBeforeCoupon != null) {
            currentPendingBeforeCoupon = Money.euros(currentPendingBeforeCoupon);
        }
        currentManualLineDiscounts = List.copyOf(
                currentManualLineDiscounts == null ? List.of() : currentManualLineDiscounts);
        currentGeneratedCoupons = List.copyOf(
                currentGeneratedCoupons == null ? List.of() : currentGeneratedCoupons);
        historicalLoyaltyLines = List.copyOf(
                historicalLoyaltyLines == null ? List.of() : historicalLoyaltyLines);
        sourceTicketNumber = sourceTicketNumber.trim();
        sourceFingerprint = sourceFingerprint.trim();
    }

    public record ManualLineDiscount(
            int position,
            UUID productId,
            BigDecimal discountPercent) {

        public ManualLineDiscount {
            Objects.requireNonNull(productId, "productId");
            discountPercent = discountPercent == null
                    ? BigDecimal.ZERO : Money.validPercentage(discountPercent);
        }
    }

    public record GeneratedCoupon(
            UUID promotionId,
            UUID memberId,
            PromotionCustomerSegment customerSegment,
            UUID memberCategoryId,
            PromotionalCouponBenefitType benefitType,
            BigDecimal amount,
            BigDecimal percent,
            BigDecimal maximumDiscount,
            BigDecimal minimumAmount,
            Instant validFrom,
            Instant validUntil) {

        public GeneratedCoupon {
            Objects.requireNonNull(promotionId, "promotionId");
            Objects.requireNonNull(benefitType, "benefitType");
            Objects.requireNonNull(validFrom, "validFrom");
            Objects.requireNonNull(validUntil, "validUntil");
        }
    }

    public record HistoricalLoyaltyLine(
            int position,
            boolean eligible,
            BigDecimal eligibleAmount) {

        public HistoricalLoyaltyLine {
            if (position < 1) {
                throw new IllegalArgumentException(
                        "position de fidelizacion debe ser positiva");
            }
            eligibleAmount = Money.euros(
                    eligibleAmount == null ? BigDecimal.ZERO : eligibleAmount);
            if (eligibleAmount.signum() < 0
                    || (!eligible && eligibleAmount.signum() != 0)) {
                throw new IllegalArgumentException(
                        "importe historico de fidelizacion no valido");
            }
        }
    }
}
