package com.tpverp.backend.security.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.tpverp.backend.security.domain.Permiso;
import com.tpverp.backend.security.domain.PermisoRepository;
import java.util.ArrayList;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CorePermissionBootstrapTest {

    @Test
    void registersCommercialPermissionsBySensitiveAction() {
        var repository = Mockito.mock(PermisoRepository.class);
        var saved = new ArrayList<Permiso>();
        when(repository.findByCodigo(any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> {
            saved.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });

        new CorePermissionBootstrap(repository).initialize();

        assertThat(saved).extracting(Permiso::getCodigo).contains(
                "PRODUCTS_READ",
                "PRODUCTS_WRITE",
                "PRODUCTS_DELETE",
                "STOCK_ADJUST",
                "CUSTOMERS_WRITE",
                "SUPPLIERS_WRITE",
                "GESTION_VENTAS",
                "GESTION_CUENTAS",
                "CASH_READ",
                "CASH_OPERATE",
                "CASH_CONFIGURE",
                "DELIVERY_NOTES_CONFIRM",
                "TICKETS_CANCEL",
                "INVOICES_CONFIRM",
                "INVOICES_PAY");
    }
}
