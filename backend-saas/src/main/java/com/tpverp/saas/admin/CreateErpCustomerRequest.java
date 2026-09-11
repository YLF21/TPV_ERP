package com.tpverp.saas.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateErpCustomerRequest(
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 64) String taxId,
        @Size(max = 320) String email,
        @Size(max = 64) String phone,
        @Size(max = 20) String documentType) {
    public CreateErpCustomerRequest(String code, String name, String taxId, String email, String phone) {
        this(code, name, taxId, email, phone, null);
    }
}
