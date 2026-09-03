package com.tpverp.backend.security.gestion;

public final class GestionGroupInvalidPasswordException extends RuntimeException {
    public static final String CODE = "GESTION_GROUP_INVALID_PASSWORD";

    public GestionGroupInvalidPasswordException() {
        super(CODE);
    }
}
