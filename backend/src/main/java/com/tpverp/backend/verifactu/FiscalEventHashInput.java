package com.tpverp.backend.verifactu;

import java.time.OffsetDateTime;

public record FiscalEventHashInput(
        String systemTaxId,
        String systemIdentifier,
        String systemId,
        String systemVersion,
        String installationNumber,
        String obligatedTaxId,
        String eventType,
        String previousHash,
        OffsetDateTime generatedAt) {
}
