package com.tpverp.backend.security.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.tpverp.backend.security.domain.Permission;
import com.tpverp.backend.security.domain.PermissionRepository;
import java.util.ArrayList;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CorePermissionBootstrapTest {

    @Test
    void registersCommercialPermissionsBySensitiveAction() {
        var repository = Mockito.mock(PermissionRepository.class);
        var saved = new ArrayList<Permission>();
        when(repository.findByCodigo(any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> {
            saved.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });

        new CorePermissionBootstrap(repository).initialize();

        assertThat(saved).extracting(Permission::getCodigo).contains(
                "PRODUCTS_READ",
                "PRODUCTS_WRITE",
                "PRODUCTS_DELETE",
                "GESTION_PRODUCTO",
                "STOCK_ADJUST",
                "CUSTOMERS_WRITE",
                "SUPPLIERS_WRITE",
                "VENTA",
                "GESTION_VENTAS",
                "GESTION_CUENTAS",
                "GESTION_USUARIO",
                "CASH_READ",
                "CASH_OPERATE",
                "CASH_CONFIGURE",
                "DELIVERY_NOTES_CONFIRM",
                "TICKETS_CANCEL",
                "INVOICES_CONFIRM",
                "INVOICES_PAY");
    }
}
