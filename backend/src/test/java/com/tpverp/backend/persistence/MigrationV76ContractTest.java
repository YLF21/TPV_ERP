package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV76ContractTest {

    private static final String MIGRATION = "db/migration/V76__catalogo_reglas_control.sql";

    @Test
    void fixesSystemNamesEnforcesOneRulePerStoreAndAddsSequenceSupport() throws IOException {
        var sql = migrationSql();

        assertThat(sql)
                .contains("consecutive_line_deletions")
                .contains("manual_price_change_over_percent")
                .contains("manual_price_changed")
                .contains("product_discount_applied")
                .contains("drop index ux_control_regla_tienda_nombre")
                .contains("create unique index ux_control_regla_tienda_tipo")
                .contains("on control_regla(tienda_id, tipo)")
                .contains("control_regla_configuracion_tipo_ck")
                .contains("create table venta_operacion_eliminacion")
                .contains("ix_venta_operacion_eliminacion_secuencia")
                .contains("vaciado_completo boolean not null")
                .contains("operacion_venta_id uuid")
                .contains("operacion_eliminacion_id uuid")
                .contains("venta_linea_eliminada_operacion_fk")
                .contains("ix_venta_linea_eliminada_secuencia")
                .doesNotContain("delete from control_regla")
                .doesNotContain("update control_evento")
                .doesNotContain("update control_alerta")
                .doesNotContain("update control_alerta_historial");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
