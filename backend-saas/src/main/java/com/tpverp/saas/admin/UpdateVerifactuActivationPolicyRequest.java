package com.tpverp.saas.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record UpdateVerifactuActivationPolicyRequest(
        @NotNull LocalDate activationDate,
        @NotBlank @Size(min = 3, max = 500) String reason) {
}
