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
        jdbc.queryForList("select id, empresa_id from tienda").forEach(row -> {
            initializeStore(row);
            ensureOpenPriceProduct((UUID) row.get("id"));
        });
    }

    @Transactional
    // Inicializa los datos comerciales obligatorios de una tienda creada despues del arranque.
    public void initializeStore(UUID storeId, UUID companyId) {
        initializeStore(Map.of("id", storeId, "empresa_id", companyId));
    }

    /**
     * Makes the generic open-price product available once the store's fiscal
     * defaults have been established. Existing catalog data is deliberately
     * left untouched, including a legacy identifier 0.
     */
    @Transactional
    public void ensureOpenPriceProduct(UUID storeId) {
        if (!jdbc.queryForList(
                "select 1 from producto_identificador where tienda_id = ? and valor = '0' limit 1",
                storeId).isEmpty()) {
            return;
        }
        var families = jdbc.queryForList(
                "select id from familia where tienda_id = ? and predeterminada = true limit 1",
                storeId);
        var taxes = jdbc.queryForList(
                "select id from impuesto_tienda where tienda_id = ? and predeterminado = true limit 1",
                storeId);
        if (families.isEmpty() || taxes.isEmpty()) {
            return;
        }
        UUID productId = UUID.randomUUID();
        UUID familyId = (UUID) families.get(0).get("id");
        UUID taxId = (UUID) taxes.get(0).get("id");
        jdbc.update(
                "insert into producto "
                        + "(id, tienda_id, familia_id, subfamilia_id, impuesto_id, nombre, precio_compra, "
                        + "impuestos_incluidos, product_type, discount_type, price_use_mode, activo) "
                        + "values (?, ?, ?, null, ?, 'ARTÍCULO VARIOS', 0, true, 'SERVICE', 'NORMAL', 'NORMAL', true)",
                productId,
                storeId,
                familyId,
                taxId);
        jdbc.update(
                "insert into producto_identificador (id, tienda_id, producto_id, tipo, valor) "
                        + "values (?, ?, ?, 'CODIGO', '0')",
                UUID.randomUUID(),
                storeId,
                productId);
        jdbc.update(
                "insert into producto_precio (id, producto_id, tarifa, importe) values (?, ?, 'VENTA', 0)",
                UUID.randomUUID(),
                productId);
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
        createPaymentMethod(companyId, "COMPENSACION_DEVOLUCION", false, false);
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
