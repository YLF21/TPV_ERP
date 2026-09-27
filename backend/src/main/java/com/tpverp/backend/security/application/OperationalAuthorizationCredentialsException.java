package com.tpverp.backend.security.application;

/** Identifies a credential failure without exposing whether a named user exists. */
public final class OperationalAuthorizationCredentialsException
        extends IllegalArgumentException {

    public enum Kind {
        CURRENT_PASSWORD,
        DELEGATED_CREDENTIALS
    }

    private final Kind kind;

    public OperationalAuthorizationCredentialsException(Kind kind) {
        super(kind == Kind.CURRENT_PASSWORD
                ? "Contrasena incorrecta"
                : "Usuario autorizador no valido");
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
