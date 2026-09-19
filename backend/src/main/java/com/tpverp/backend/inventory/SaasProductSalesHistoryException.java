package com.tpverp.backend.inventory;

import org.springframework.http.HttpStatus;

public final class SaasProductSalesHistoryException extends RuntimeException {
    private final HttpStatus status;
    SaasProductSalesHistoryException(HttpStatus status, String code) { super(code); this.status = status; }
    public HttpStatus status() { return status; }
    static SaasProductSalesHistoryException unavailable() {
        return new SaasProductSalesHistoryException(HttpStatus.SERVICE_UNAVAILABLE, "SAAS_PRODUCT_HISTORY_UNAVAILABLE");
    }
    static SaasProductSalesHistoryException invalidResponse() {
        return new SaasProductSalesHistoryException(HttpStatus.BAD_GATEWAY, "SAAS_PRODUCT_HISTORY_INVALID_RESPONSE");
    }
    static SaasProductSalesHistoryException limit() {
        return new SaasProductSalesHistoryException(HttpStatus.UNPROCESSABLE_CONTENT, "product_history_export_limit_exceeded");
    }
}
