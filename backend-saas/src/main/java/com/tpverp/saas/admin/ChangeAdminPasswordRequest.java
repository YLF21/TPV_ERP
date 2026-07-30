package com.tpverp.saas.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangeAdminPasswordRequest(@NotBlank @Size(min = 10, max = 120) String password) {
}
