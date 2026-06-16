package com.tpverp.backend.verifactu;

public record VerifactuTransportResponse(int httpStatus, String body) {

    public VerifactuTransportResponse {
        body = body == null ? "" : body;
    }
}
