package com.tpverp.backend.promotion;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PromotionPreviewRequest(
        LocalDate saleDate,
        UUID customerId,
        UUID memberId,
        UUID memberCategoryId,
        @NotEmpty @Valid List<Line> lines) {

    public PromotionPreviewRequest {
        lines = List.copyOf(lines == null ? List.of() : lines);
    }

    public record Line(
            int position,
            @NotNull UUID productId,
            UUID familyId,
            UUID subfamilyId,
            @NotNull @Positive BigDecimal quantity,
            @NotNull @PositiveOrZero BigDecimal unitPrice,
            boolean taxIncluded,
            String taxRegime,
            @NotNull @PositiveOrZero BigDecimal taxPercent,
            Boolean manualDiscount,
            Boolean discountable) {

        PromotionEvaluationLine toEvaluationLine() {
            return new PromotionEvaluationLine(
                    position,
                    productId,
                    familyId,
                    subfamilyId,
                    quantity,
                    unitPrice,
                    taxIncluded,
                    taxRegime == null || taxRegime.isBlank() ? "GENERAL" : taxRegime,
                    taxPercent,
                    Boolean.TRUE.equals(manualDiscount),
                    discountable == null || discountable);
        }
    }
}
