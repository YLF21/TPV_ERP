package com.tpverp.backend.verifactu;

import java.util.UUID;

public interface VerifactuTransport {

    VerifactuTransportResponse send(String endpoint, String soapEnvelope);

    /** Fiscal identity is explicit; implementations must not infer it from a UI session. */
    default VerifactuTransportResponse send(
            UUID companyId, UUID installationId, String endpoint, String soapEnvelope) {
        if (companyId == null || installationId == null) {
            throw new IllegalArgumentException("companyId e installationId son obligatorios");
        }
        return send(endpoint, soapEnvelope);
    }
}
