package com.tpverp.saas.admin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties("roleName")
public record CreateAdminUserRequest(
        @NotBlank @Size(max = 80) String username,
        @NotBlank @Size(min = 4, max = 120) String password) {
}
