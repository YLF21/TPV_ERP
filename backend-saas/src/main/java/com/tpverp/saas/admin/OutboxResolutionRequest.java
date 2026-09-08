package com.tpverp.saas.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OutboxResolutionRequest(
        @NotBlank @Size(min = 5, max = 500) String reason) {
}
