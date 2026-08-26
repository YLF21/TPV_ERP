package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.util.UUID;

/** Frozen evidence metadata. XML and certificate material are never serialized. */
public record FiscalRecordArtifactView(
        FiscalMode fiscalMode,
        FiscalEndpointEnvironment environment,
        boolean sandbox,
        UUID systemVersionId,
        String issuerName,
        String issuerTaxId,
        String xmlHash,
        String qrUrl,
        String qrHash,
        Instant createdAt) {
}
