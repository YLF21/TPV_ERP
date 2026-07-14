package com.tpverp.backend.installation;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

class CommercialBootstrapServiceTest {

    @Test
    void createsProtectedCommercialDefaultsForEveryStore() {
        var jdbc = Mockito.mock(JdbcTemplate.class);
        var storeId = UUID.randomUUID();
        var companyId = UUID.randomUUID();
        when(jdbc.queryForList("select id, empresa_id from tienda"))
                .thenReturn(List.of(Map.of("id", storeId, "empresa_id", companyId)));

        new CommercialBootstrapService(jdbc).initialize();

        verify(jdbc).update(
                "insert into almacen (id, tienda_id, nombre, predeterminado, activo) "
                        + "values (?, ?, 'GENERAL', true, true) on conflict do nothing",
                storeId,
                storeId);
        verify(jdbc).update(
                "insert into familia (id, tienda_id, family_id, nombre, predeterminada) "
                        + "values (?, ?, 'GENERAL', 'GENERAL', true) on conflict do nothing",
                storeId,
                storeId);
        verify(jdbc).update(
                Mockito.eq("insert into metodo_pago "
                        + "(id, empresa_id, nombre, protegido, activo, requiere_referencia, abre_caja_registradora) "
                        + "values (?, ?, ?, true, true, ?, ?) on conflict do nothing"),
                Mockito.any(UUID.class),
                Mockito.eq(companyId),
                Mockito.eq("EFECTIVO"),
                Mockito.eq(false),
                Mockito.eq(true));
    }
}
