package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV39ContractTest {

    private static final String MIGRATION = "db/migration/V39__configuracion_terminales_y_datafonos.sql";

    @Test
    void agregaConfiguracionDeTerminalesYMetadatosDeDatafono() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("'configuracion_terminal'")
                .contains("create table configuracion_pago_tienda")
                .contains("card_manual_enabled boolean not null default true")
                .contains("manual_fallback_enabled boolean not null default true")
                .contains("create table configuracion_pago_terminal")
                .contains("terminal_id uuid not null references terminal(id)")
                .contains("card_mode varchar(16) not null")
                .contains("provider varchar(32) not null")
                .contains("provider_parameters jsonb not null default '{}'::jsonb")
                .contains("alter table documento_pago")
                .contains("add column terminal_pago_modo varchar(16)")
                .contains("add column terminal_pago_provider varchar(32)")
                .contains("add column terminal_pago_estado varchar(16)")
                .contains("add column autorizacion_tarjeta varchar(64)")
                .contains("add column terminal_cobro_id uuid references terminal(id)");
        assertThat(sql)
                .contains("terminal_pago_modo <> 'manual'")
                .contains("terminal_pago_provider is null")
                .contains("terminal_pago_provider = 'none'")
                .contains("terminal_pago_modo <> 'integrated'")
                .contains("terminal_pago_provider is not null")
                .contains("terminal_pago_provider <> 'none'")
                .contains("terminal_cobro_id is not null");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
