package com.tpverp.frontend.common.security;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionRulesTest {

    @Test
    void allowsAppVentaEntryWithSalesPermission() {
        assertTrue(PermissionRules.canEnterAppVenta(Set.of(Permission.VENTA)));
        assertTrue(PermissionRules.canEnterAppVenta(Set.of(Permission.DELIVERY_NOTES_WRITE)));
        assertTrue(PermissionRules.canEnterAppVenta(Set.of(Permission.ADMIN)));
    }

    @Test
    void productManagementAloneDoesNotEnterAppVenta() {
        assertFalse(PermissionRules.canEnterAppVenta(Set.of(Permission.GESTION_PRODUCTO)));
    }

    @Test
    void restrictedActionsCanUseSellerOrAuthorizerPermission() {
        assertTrue(PermissionRules.canApplyDiscount(Set.of(Permission.APLICAR_DESCUENTO), Set.of()));
        assertTrue(PermissionRules.canApplyDiscount(Set.of(), Set.of(Permission.APLICAR_DESCUENTO)));
        assertFalse(PermissionRules.canApplyDiscount(Set.of(Permission.VENTA), Set.of(Permission.VENTA)));

        assertTrue(PermissionRules.canChangePrice(Set.of(Permission.CAMBIAR_PRECIO), Set.of()));
        assertTrue(PermissionRules.canChangePrice(Set.of(), Set.of(Permission.CAMBIAR_PRECIO)));
        assertFalse(PermissionRules.canChangePrice(Set.of(Permission.VENTA), Set.of(Permission.APLICAR_DESCUENTO)));
    }

    @Test
    void productManagementRequiresProductPermissionOrAdmin() {
        assertTrue(PermissionRules.canManageProduct(Set.of(Permission.GESTION_PRODUCTO)));
        assertTrue(PermissionRules.canManageProduct(Set.of(Permission.ADMIN)));
        assertFalse(PermissionRules.canManageProduct(Set.of(Permission.VENTA)));
    }
}
