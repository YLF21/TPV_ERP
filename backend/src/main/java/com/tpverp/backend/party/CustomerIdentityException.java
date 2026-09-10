package com.tpverp.backend.party;

import org.springframework.http.HttpStatus;

/** Public, sanitized customer identity failures; never includes the received identity or token. */
public final class CustomerIdentityException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public CustomerIdentityException(String code, HttpStatus status) {
        super(code);
        this.code = code;
        this.status = status;
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }

    public static CustomerIdentityException invalid() {
        return new CustomerIdentityException("CUSTOMER_DOCUMENT_INVALID", HttpStatus.UNPROCESSABLE_CONTENT);
    }
    public static CustomerIdentityException duplicate() {
        return new CustomerIdentityException("CUSTOMER_DOCUMENT_DUPLICATE", HttpStatus.CONFLICT);
    }
    public static CustomerIdentityException conflict() {
        return new CustomerIdentityException("CUSTOMER_IDENTITY_CONFLICT", HttpStatus.CONFLICT);
    }
    public static CustomerIdentityException unavailable() {
        return new CustomerIdentityException("CUSTOMER_IDENTITY_SAAS_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
