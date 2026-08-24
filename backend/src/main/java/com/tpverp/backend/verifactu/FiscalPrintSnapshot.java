package com.tpverp.backend.verifactu;

import java.util.Objects;

/** Immutable print contract used for first print and every reprint. */
public record FiscalPrintSnapshot(
        String formatVersion,
        String generatorVersion,
        FiscalMode mode,
        FiscalEndpointEnvironment environment,
        String qrUrl,
        String qrPayloadSha256,
        String prefix,
        String legend,
        String testNotice) {

    public FiscalPrintSnapshot {
        formatVersion = required(formatVersion, "formatVersion");
        generatorVersion = required(generatorVersion, "generatorVersion");
        mode = Objects.requireNonNull(mode, "mode");
        environment = Objects.requireNonNull(environment, "environment");
        qrUrl = required(qrUrl, "qrUrl");
        qrPayloadSha256 = required(qrPayloadSha256, "qrPayloadSha256");
        prefix = required(prefix, "prefix");
        if (mode == FiscalMode.VERIFACTU && (legend == null || legend.isBlank())) {
            throw new IllegalArgumentException("VERI*FACTU requiere leyenda fiscal");
        }
        if (mode == FiscalMode.NO_VERIFACTU && legend != null) {
            throw new IllegalArgumentException("NO VERI*FACTU no puede llevar leyenda verificable");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }
}
