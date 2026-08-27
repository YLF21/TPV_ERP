package com.tpverp.saas.tenant;

import java.util.Locale;

public enum TenantRole {
    OWNER(true),
    MANAGER(true),
    VIEWER(false),
    BILLING(false);

    private final boolean erpWriteAllowed;

    TenantRole(boolean erpWriteAllowed) {
        this.erpWriteAllowed = erpWriteAllowed;
    }

    public boolean canWriteErpMasters() {
        return erpWriteAllowed;
    }

    public boolean canBeAssignedByAdmin() {
        return this != OWNER;
    }

    public static TenantRole parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Rol cliente requerido");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Rol cliente no valido", exception);
        }
    }
}
