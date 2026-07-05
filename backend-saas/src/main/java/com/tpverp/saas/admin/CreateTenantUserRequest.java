package com.tpverp.saas.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTenantUserRequest(
        @NotBlank @Size(max = 80) String username,
        @NotBlank @Size(min = 6, max = 120) String password,
        @NotBlank @Size(max = 40) String roleName) {
}
