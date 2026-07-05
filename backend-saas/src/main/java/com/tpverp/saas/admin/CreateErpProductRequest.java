package com.tpverp.saas.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateErpProductRequest(
        @NotBlank @Size(max = 60) String sku,
        @NotBlank @Size(max = 180) String name,
        @Size(max = 100) String category,
        @NotBlank @Size(max = 32) String price,
        @NotBlank @Size(max = 32) String taxRate,
        @NotBlank @Size(max = 32) String minStock) {
}
