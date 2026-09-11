package com.tpverp.backend.document;

import org.springframework.http.HttpStatus;

/** Only stable local codes leave the adapter; upstream bodies and credentials never become causes. */
public final class SaasCustomerDocumentException extends RuntimeException {
    private final HttpStatus status;
    SaasCustomerDocumentException(HttpStatus status, String code) { super(code); this.status = status; }
    public HttpStatus status() { return status; }
    static SaasCustomerDocumentException unavailable() {
        return new SaasCustomerDocumentException(HttpStatus.SERVICE_UNAVAILABLE, "SAAS_CUSTOMER_DOCUMENTS_UNAVAILABLE");
    }
    static SaasCustomerDocumentException invalidResponse() {
        return new SaasCustomerDocumentException(HttpStatus.BAD_GATEWAY, "SAAS_CUSTOMER_DOCUMENTS_INVALID_RESPONSE");
    }
    static SaasCustomerDocumentException binding() {
        return new SaasCustomerDocumentException(HttpStatus.CONFLICT, "SAAS_CUSTOMER_BINDING_REQUIRED");
    }
    static SaasCustomerDocumentException limit() {
        return new SaasCustomerDocumentException(HttpStatus.UNPROCESSABLE_CONTENT, "customer_documents_export_limit_exceeded");
    }
}
