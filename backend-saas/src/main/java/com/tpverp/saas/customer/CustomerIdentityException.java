package com.tpverp.saas.customer;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Stable, non-identifying errors shared by installation and master write APIs. */
public class CustomerIdentityException extends ResponseStatusException {
    private final String code;

    private CustomerIdentityException(HttpStatus status, String code, String detail) {
        super(status, detail);
        this.code = code;
    }

    public String getCode() { return code; }

    public static CustomerIdentityException invalid() {
        return new CustomerIdentityException(HttpStatus.BAD_REQUEST, "CUSTOMER_DOCUMENT_INVALID",
                "El tipo o numero del documento del cliente no es valido");
    }

    public static CustomerIdentityException duplicate() {
        return new CustomerIdentityException(HttpStatus.CONFLICT, "CUSTOMER_DOCUMENT_DUPLICATE",
                "El documento del cliente ya esta registrado o reservado en esta empresa");
    }

    public static CustomerIdentityException conflict() {
        return new CustomerIdentityException(HttpStatus.CONFLICT, "CUSTOMER_IDENTITY_CONFLICT",
                "La identidad del cliente ha cambiado o tiene otra operacion pendiente");
    }

    public static CustomerIdentityException notFound() {
        return new CustomerIdentityException(HttpStatus.NOT_FOUND, "CUSTOMER_CENTRAL_NOT_FOUND",
                "No existe un cliente central con ese documento en esta empresa");
    }
}
