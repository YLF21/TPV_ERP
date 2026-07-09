package com.tpverp.backend.promotion;

import jakarta.validation.constraints.NotBlank;

public record PromotionalCouponAdminActionRequest(@NotBlank String reason) {
}
