package com.tpverp.backend.verifactu;

public class VerifactuTransportException extends RuntimeException {

    public VerifactuTransportException(String message) {
        super(message);
    }

    public VerifactuTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
