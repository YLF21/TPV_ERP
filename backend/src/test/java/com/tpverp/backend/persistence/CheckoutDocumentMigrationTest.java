package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CheckoutDocumentMigrationTest {

    @Test
    void paymentMetadataKeepsCashDeliveryAndChangeConsistent() throws Exception {
        var sql = migration("db/migration/V98__checkout_payment_metadata.sql");

        assertThat(sql).contains(
                "add column delivered numeric(19,2)",
                "add column change numeric(19,2)",
                "add column comment varchar(512)",
                "add column comentario varchar(512)",
                "delivered >= amount",
                "change = delivered - amount");
    }

    @Test
    void pendingTicketIsExplicitlyMarkedAndIndexedForCustomerDebtQueries() throws Exception {
        var sql = migration("db/migration/V99__ticket_cliente_pendiente.sql");

        assertThat(sql).contains(
                "add column cuenta_cobrar boolean not null default false",
                "where tipo = 'ticket' and cuenta_cobrar = true");
    }

    @Test
    void fixedCheckoutDiscountHasARestrictedFiscalLineType() throws Exception {
        var sql = migration("db/migration/V100__descuento_fijo_checkout.sql");

        assertThat(sql).contains(
                "'manual_discount'",
                "producto_id is null",
                "promocion_id is null",
                "promocion_version_id is null",
                "cupon_promocional_id is null",
                "total < 0");
    }

    @Test
    void cashDrawerPermissionAndControlTypesAreMigratedTogether() throws Exception {
        var sql = migration("db/migration/V101__permiso_y_alerta_apertura_cajon.sql");

        assertThat(sql).contains(
                "'abrir_cajon'",
                "'cash.permissions.opendrawer'",
                "'manual_negative_quantity'",
                "'cash_drawer_opened'");
    }

    @Test
    void productEditControlTypeIsAcceptedByTheDatabaseConstraints() throws Exception {
        var sql = migration("db/migration/V102__alerta_modificacion_producto_desde_venta.sql");

        assertThat(sql).contains(
                "'product_catalog_modified'",
                "control_regla_tipo_ck",
                "control_regla_configuracion_tipo_ck");
    }

    @Test
    void cashSessionDiscrepancyControlTypeIsAcceptedByTheDatabaseConstraints() throws Exception {
        var sql = migration("db/migration/V115__alerta_descuadre_caja.sql");

        assertThat(sql).contains(
                "'cash_session_discrepancy'",
                "control_regla_tipo_ck",
                "control_regla_configuracion_tipo_ck");
    }

    @Test
    void documentLineSerialNumbersAreOrderedUniqueAndSearchable() throws Exception {
        var sql = migration("db/migration/V103__numeros_serie_lineas_documento.sql");

        assertThat(sql).contains(
                "create table if not exists documento_linea_numero_serie",
                "references documento_linea(id) on delete cascade",
                "primary key (documento_linea_id, posicion)",
                "upper(btrim(numero_serie))",
                "idx_documento_linea_numero_serie_busqueda");
    }

    @Test
    void voucherCodeAndExternalReferenceAreSeparatedDuringCheckout() throws Exception {
        var sql = migration("db/migration/V105__codigo_vale_separado_en_checkout.sql");

        assertThat(sql).contains(
                "add column voucher_code varchar(128)",
                "set voucher_code = reference",
                "reference = null",
                "where kind = 'voucher'",
                "idx_sale_payment_allocation_voucher_code");
    }

    @Test
    void voucherExternalReferenceConfigurationIsCleared() throws Exception {
        var sql = migration("db/migration/V106__vale_sin_referencia_externa.sql");

        assertThat(sql).contains(
                "update metodo_pago",
                "set requiere_referencia = false",
                "where nombre = 'vale'");
    }

    private String migration(String resource) throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(stream).as("Debe existir %s", resource).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
