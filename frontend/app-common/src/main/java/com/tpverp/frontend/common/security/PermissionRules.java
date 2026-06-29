package com.tpverp.frontend.common.security;

import java.util.Set;

public final class PermissionRules {

    private static final Set<Permission> APP_VENTA_ENTRY = Set.of(
            Permission.ADMIN,
            Permission.VENTA,
            Permission.GESTION_VENTAS,
            Permission.TICKETS_CREATE,
            Permission.INVOICES_WRITE,
            Permission.DELIVERY_NOTES_WRITE
    );

    private PermissionRules() {
    }

    public static boolean canEnterAppVenta(Set<Permission> permissions) {
        return hasAny(permissions, APP_VENTA_ENTRY);
    }

    public static boolean canApplyDiscount(Set<Permission> sellerPermissions, Set<Permission> authorizerPermissions) {
        return hasAny(sellerPermissions, Set.of(Permission.ADMIN, Permission.APLICAR_DESCUENTO))
                || hasAny(authorizerPermissions, Set.of(Permission.ADMIN, Permission.APLICAR_DESCUENTO));
    }

    public static boolean canChangePrice(Set<Permission> sellerPermissions, Set<Permission> authorizerPermissions) {
        return hasAny(sellerPermissions, Set.of(Permission.ADMIN, Permission.CAMBIAR_PRECIO))
                || hasAny(authorizerPermissions, Set.of(Permission.ADMIN, Permission.CAMBIAR_PRECIO));
    }

    public static boolean canManageProduct(Set<Permission> permissions) {
        return hasAny(permissions, Set.of(Permission.ADMIN, Permission.GESTION_PRODUCTO));
    }

    public static boolean canClearParkedSales(Set<Permission> permissions) {
        return hasAny(permissions, Set.of(Permission.ADMIN, Permission.GESTION_VENTAS));
    }

    private static boolean hasAny(Set<Permission> permissions, Set<Permission> accepted) {
        return permissions.stream().anyMatch(accepted::contains);
    }
}
