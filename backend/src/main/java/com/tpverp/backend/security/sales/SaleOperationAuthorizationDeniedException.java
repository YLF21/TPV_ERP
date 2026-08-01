package com.tpverp.backend.security.sales;

import org.springframework.security.access.AccessDeniedException;

public final class SaleOperationAuthorizationDeniedException
        extends AccessDeniedException {

    public static final String CODE = "SALE_OPERATION_AUTHORIZATION_DENIED";

    public SaleOperationAuthorizationDeniedException(Throwable cause) {
        super("La autorizacion operativa ha sido rechazada", cause);
    }
}
