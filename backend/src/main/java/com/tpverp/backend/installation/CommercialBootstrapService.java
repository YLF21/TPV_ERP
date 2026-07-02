package com.tpverp.backend.installation;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class CommercialBootstrapService {

    private final JdbcTemplate jdbc;

    public CommercialBootstrapService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void initialize() {
        jdbc.queryForList("select id, empresa_id from tienda").forEach(this::initializeStore);
    }

    private void initializeStore(Map<String, Object> row) {
        UUID storeId = (UUID) row.get("id");
        UUID companyId = (UUID) row.get("empresa_id");
        jdbc.update(
                "insert into almacen (id, tienda_id, nombre, predeterminado, activo) "
                        + "values (?, ?, 'GENERAL', true, true) on conflict do nothing",
                storeId,
                storeId);
        jdbc.update(
                "insert into familia (id, tienda_id, nombre, predeterminada) "
                        + "values (?, ?, 'GENERAL', true) on conflict do nothing",
                storeId,
                storeId);
        jdbc.update(
                "insert into impuesto_tienda "
                        + "(id, tienda_id, porcentaje, activo, predeterminado) "
                        + "values (?, ?, ?, true, true) on conflict do nothing",
                UUID.randomUUID(),
                storeId,
                new BigDecimal("21.00"));
        createPaymentMethod(companyId, "EFECTIVO");
        createPaymentMethod(companyId, "TARJETA");
        createPaymentMethod(companyId, "VALE");
        createPaymentMethod(companyId, "SALDO_MIEMBRO");
    }

    private void createPaymentMethod(UUID companyId, String name) {
        jdbc.update(
                "insert into metodo_pago (id, empresa_id, nombre, protegido, activo) "
                        + "values (?, ?, ?, true, true) on conflict do nothing",
                UUID.randomUUID(),
                companyId,
                name);
    }
}
