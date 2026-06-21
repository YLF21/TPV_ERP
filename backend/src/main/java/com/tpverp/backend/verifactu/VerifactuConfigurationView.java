package com.tpverp.backend.verifactu;

import java.time.Instant;

public record VerifactuConfigurationView(
        boolean voluntarilyActive,
        Instant activatedAt,
        Instant firstSubmissionAt) {

    public static VerifactuConfigurationView from(VerifactuConfiguration configuration) {
        return new VerifactuConfigurationView(
                configuration.isVoluntarilyActive(),
                configuration.getActivatedAt(),
                configuration.getFirstSubmissionAt());
    }
    // Expone solo el estado funcional de activacion VERI*FACTU.
}
