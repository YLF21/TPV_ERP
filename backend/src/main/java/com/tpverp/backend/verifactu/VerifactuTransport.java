package com.tpverp.backend.verifactu;

public interface VerifactuTransport {

    VerifactuTransportResponse send(String endpoint, String soapEnvelope);
}
