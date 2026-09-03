package com.tpverp.backend.security.gestion;

public final class GestionGroupLockedException extends RuntimeException {
    public static final String CODE = "GESTION_GROUP_LOCKED";

    private final GestionGroup group;

    public GestionGroupLockedException(GestionGroup group) {
        super(CODE);
        this.group = group;
    }

    public GestionGroup group() {
        return group;
    }
}
