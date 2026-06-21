package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.util.UUID;

public record FiscalCorrectionView(
        UUID id,
        UUID originalRecordId,
        String number,
        Instant generatedAt,
        FiscalSubmissionStatus status) {

    public static FiscalCorrectionView pending(
            FiscalRecord correction, UUID originalRecordId) {
        return new FiscalCorrectionView(
                correction.getId(), originalRecordId, correction.getNumber(),
                correction.getGeneratedAt(), FiscalSubmissionStatus.PENDIENTE);
    }
    // Devuelve el estado inicial persistido antes del envio asincrono.
}
