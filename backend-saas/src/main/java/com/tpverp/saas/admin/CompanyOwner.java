package com.tpverp.saas.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompanyOwner(
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Size(max = 32) String taxId,
        @Size(max = 40) String phone,
        @Email @Size(max = 160) String email) {
}
