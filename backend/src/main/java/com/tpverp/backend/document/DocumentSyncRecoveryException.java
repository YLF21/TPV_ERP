package com.tpverp.backend.document;

import org.springframework.http.HttpStatus;

public final class DocumentSyncRecoveryException extends RuntimeException {
    private final HttpStatus status;
    DocumentSyncRecoveryException(HttpStatus status, String code) { super(code); this.status = status; }
    public HttpStatus status() { return status; }
    static DocumentSyncRecoveryException invalid() {
        return new DocumentSyncRecoveryException(HttpStatus.BAD_REQUEST, "DOCUMENT_RECOVERY_INVALID_REQUEST");
    }
    static DocumentSyncRecoveryException conflict(String code) {
        return new DocumentSyncRecoveryException(HttpStatus.CONFLICT, code);
    }
    static DocumentSyncRecoveryException unavailable() {
        return new DocumentSyncRecoveryException(HttpStatus.SERVICE_UNAVAILABLE, "DOCUMENT_RECOVERY_SAAS_UNAVAILABLE");
    }
    static DocumentSyncRecoveryException malformed() {
        return new DocumentSyncRecoveryException(HttpStatus.BAD_GATEWAY, "DOCUMENT_RECOVERY_SAAS_INVALID_RESPONSE");
    }
}
