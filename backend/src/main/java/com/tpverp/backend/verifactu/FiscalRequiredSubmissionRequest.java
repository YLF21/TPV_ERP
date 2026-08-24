package com.tpverp.backend.verifactu;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FiscalRequiredSubmissionRequest(
        @NotBlank @Size(max = 18) String reference) {
}
