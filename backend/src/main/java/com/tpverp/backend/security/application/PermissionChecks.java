package com.tpverp.backend.security.application;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

public final class PermissionChecks {

    private PermissionChecks() {
    }

    public static boolean hasProductManagement(Authentication authentication) {
        return hasRole(authentication, "ADMIN") || hasAuthority(authentication, CorePermissionBootstrap.GESTION_PRODUCTO);
    }

    public static boolean hasWarehouseManagement(Authentication authentication) {
        return hasRole(authentication, "ADMIN") || hasAuthority(authentication, CorePermissionBootstrap.GESTION_ALMACEN);
    }

    public static boolean hasPurchaseDocumentRead(Authentication authentication) {
        return hasRole(authentication, "ADMIN")
                || hasAuthority(authentication, CorePermissionBootstrap.GESTION_PRODUCTO)
                || hasAuthority(authentication, CorePermissionBootstrap.GESTION_ALMACEN)
                || hasAuthority(authentication, CorePermissionBootstrap.GESTION_CUENTAS);
    }

    public static boolean hasPurchaseDocumentWrite(Authentication authentication) {
        return hasRole(authentication, "ADMIN")
                || hasAuthority(authentication, CorePermissionBootstrap.GESTION_PRODUCTO)
                || hasAuthority(authentication, CorePermissionBootstrap.GESTION_ALMACEN);
    }

    public static boolean hasSalesDocumentRead(Authentication authentication, String specificPermission) {
        return hasRole(authentication, "ADMIN")
                || hasAuthority(authentication, CorePermissionBootstrap.GESTION_VENTAS)
                || hasAuthority(authentication, CorePermissionBootstrap.VENTA)
                || hasAuthority(authentication, specificPermission);
    }

    public static boolean hasAnyAuthority(Authentication authentication, String... authorities) {
        if (authentication == null || authorities == null) {
            return false;
        }
        for (String authority : authorities) {
            if (hasAuthority(authentication, authority)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasAuthority(Authentication authentication, String authority) {
        return authentication != null
                && authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .anyMatch(authority::equals);
    }

    public static boolean hasRole(Authentication authentication, String role) {
        return hasAuthority(authentication, "ROLE_" + role);
    }
}
