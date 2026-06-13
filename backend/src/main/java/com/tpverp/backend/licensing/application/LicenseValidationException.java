package com.tpverp.backend.licensing.application;

public class LicenseValidationException extends RuntimeException {

    public LicenseValidationException(String message) {
        super(message);
    }

    public LicenseValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
