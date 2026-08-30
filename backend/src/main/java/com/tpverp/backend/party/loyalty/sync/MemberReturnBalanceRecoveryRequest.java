package com.tpverp.backend.party.loyalty.sync;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record MemberReturnBalanceRecoveryRequest(
        @NotNull UUID expectedMovementId,
        @NotNull @DecimalMin(value = "0.01") BigDecimal expectedAmount,
        @NotBlank @Size(max = 128) String expectedFingerprint,
        @NotBlank @Size(max = 500) String reason) {
}
