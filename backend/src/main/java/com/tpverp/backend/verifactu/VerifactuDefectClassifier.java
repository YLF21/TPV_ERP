package com.tpverp.backend.verifactu;

import org.springframework.stereotype.Component;

@Component
public class VerifactuDefectClassifier {

    public VerifactuDefectKind classify(String errorCode) {
        if ("INVALID_AEAT_RESPONSE".equals(errorCode)) {
            return VerifactuDefectKind.RETRYABLE_TECHNICAL;
        }
        return VerifactuDefectKind.TECHNICAL_REVIEW;
    }
}
