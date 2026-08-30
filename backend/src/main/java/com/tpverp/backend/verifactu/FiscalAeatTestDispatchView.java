package com.tpverp.backend.verifactu;

import java.util.UUID;

/** Sanitized result; no certificate, password, key or SOAP content is exposed. */
public record FiscalAeatTestDispatchView(
        boolean processed,
        FiscalSubmissionStatus status,
        String errorCode,
        String error,
        EvidenceMetadata evidence) {

    public record EvidenceMetadata(
            String releaseId,
            FiscalRuntimeClass runtimeClass,
            FiscalEndpointEnvironment endpointEnvironment,
            FiscalTransportMode transport,
            UUID companyId,
            UUID installationId,
            UUID recordId,
            boolean networkRequestIssued,
            boolean certificateMaterialRedacted) {
    }
}
