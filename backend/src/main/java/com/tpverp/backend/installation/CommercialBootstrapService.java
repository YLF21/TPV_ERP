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

    @Transactional
    // Inicializa los datos comerciales obligatorios de una tienda creada despues del arranque.
    public void initializeStore(UUID storeId, UUID companyId) {
        initializeStore(Map.of("id", storeId, "empresa_id", companyId));
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
                "insert into familia (id, tienda_id, family_id, nombre, predeterminada) "
                        + "values (?, ?, 'GENERAL', 'GENERAL', true) on conflict do nothing",
                storeId,
                storeId);
        jdbc.update(
                "insert into impuesto_tienda "
                        + "(id, tienda_id, porcentaje, activo, predeterminado) "
                        + "values (?, ?, ?, true, true) on conflict do nothing",
                UUID.randomUUID(),
                storeId,
                new BigDecimal("21.00"));
        createPaymentMethod(companyId, "EFECTIVO", false, true);
        createPaymentMethod(companyId, "TARJETA", false, false);
        createPaymentMethod(companyId, "TRANSFERENCIA", false, false);
        createPaymentMethod(companyId, "VALE", false, false);
        createPaymentMethod(companyId, "DESCUENTO", false, false);
        createPaymentMethod(companyId, "OTRO", false, false);
        createPaymentMethod(companyId, "SALDO_MIEMBRO", false, false);
    }

    private void createPaymentMethod(
            UUID companyId, String name, boolean requiresReference, boolean opensCashDrawer) {
        jdbc.update(
                "insert into metodo_pago "
                        + "(id, empresa_id, nombre, protegido, activo, requiere_referencia, abre_caja_registradora) "
                        + "values (?, ?, ?, true, true, ?, ?) on conflict do nothing",
                UUID.randomUUID(),
                companyId,
                name,
                requiresReference,
                opensCashDrawer);
    }
}
