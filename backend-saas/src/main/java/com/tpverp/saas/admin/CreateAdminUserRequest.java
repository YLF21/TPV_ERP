package com.tpverp.saas.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAdminUserRequest(
        @NotBlank @Size(max = 80) String username,
        @NotBlank @Size(min = 4, max = 80) String password,
        @NotBlank @Size(max = 80) String roleName) {
}
