package com.tpverp.backend.verifactu;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Decision-complete request for the deliberately narrow AEAT TEST action. */
public record FiscalAeatTestDispatchRequest(
        @NotNull UUID companyId,
        @NotNull UUID installationId,
        UUID recordId,
        @NotBlank String expectedReleaseId,
        @NotBlank String confirmation) {
}
