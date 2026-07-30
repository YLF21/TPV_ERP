package com.tpverp.saas.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SaasLoginRequest(
        @NotBlank @Size(max = 80) String username,
        @NotBlank @Size(max = 120) String password) {
}
