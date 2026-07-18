package com.tpverp.backend.security.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class PermissionChecksTest {

    @Test
    void warehouseManagementRequiresItsUnifiedPermissionOrAdminRole() {
        assertThat(PermissionChecks.hasWarehouseManagement(authentication("GESTION_ALMACEN"))).isTrue();
        assertThat(PermissionChecks.hasWarehouseManagement(authentication("ROLE_ADMIN"))).isTrue();
        assertThat(PermissionChecks.hasWarehouseManagement(authentication("GESTION_PRODUCTO"))).isFalse();
        assertThat(PermissionChecks.hasWarehouseManagement(authentication("GESTION_VENTAS"))).isFalse();
    }

    @Test
    void purchaseDocumentReadIncludesAccountsButWriteDoesNot() {
        assertThat(PermissionChecks.hasPurchaseDocumentRead(authentication("GESTION_PRODUCTO"))).isTrue();
        assertThat(PermissionChecks.hasPurchaseDocumentRead(authentication("GESTION_ALMACEN"))).isTrue();
        assertThat(PermissionChecks.hasPurchaseDocumentRead(authentication("GESTION_CUENTAS"))).isTrue();
        assertThat(PermissionChecks.hasPurchaseDocumentRead(authentication("GESTION_VENTAS"))).isFalse();

        assertThat(PermissionChecks.hasPurchaseDocumentWrite(authentication("GESTION_PRODUCTO"))).isTrue();
        assertThat(PermissionChecks.hasPurchaseDocumentWrite(authentication("GESTION_ALMACEN"))).isTrue();
        assertThat(PermissionChecks.hasPurchaseDocumentWrite(authentication("GESTION_CUENTAS"))).isFalse();
        assertThat(PermissionChecks.hasPurchaseDocumentWrite(authentication("GESTION_VENTAS"))).isFalse();
    }

    @Test
    void purchasePriceManagementDoesNotLeakToSalesOrWarehouse() {
        assertThat(PermissionChecks.hasProductManagement(authentication("GESTION_PRODUCTO"))).isTrue();
        assertThat(PermissionChecks.hasProductManagement(authentication("GESTION_ALMACEN"))).isFalse();
        assertThat(PermissionChecks.hasProductManagement(authentication("GESTION_VENTAS"))).isFalse();
    }

    private UsernamePasswordAuthenticationToken authentication(String... authorities) {
        return new UsernamePasswordAuthenticationToken(
                "user",
                "n/a",
                List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList());
    }
}
