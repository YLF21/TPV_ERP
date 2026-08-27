package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.util.UUID;

/** Sanitized event summary for APP GESTION lists. XML is exposed only by explicit downloads. */
public record FiscalEventView(
        UUID id,
        UUID installationId,
        UUID systemVersionId,
        long sequence,
        FiscalEventType type,
        FiscalMode fiscalMode,
        Instant generatedAt,
        String previousHash,
        String hash,
        String xmlHash,
        boolean signed) {

    public static FiscalEventView from(FiscalEvent event) {
        return new FiscalEventView(
                event.getId(),
                event.getInstallationId(),
                event.getSystemVersionId(),
                event.getSequence(),
                event.getType(),
                event.getFiscalMode(),
                event.getGeneratedAt(),
                event.getPreviousHash(),
                event.getHash(),
                event.getXmlHash(),
                event.getSignedXml() != null && !event.getSignedXml().isBlank());
    }
}
