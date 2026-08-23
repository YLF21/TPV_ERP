package com.tpverp.backend.verifactu;

import jakarta.validation.constraints.NotBlank;

public record FiscalRequiredSubmissionRequest(@NotBlank String reference) {
}
