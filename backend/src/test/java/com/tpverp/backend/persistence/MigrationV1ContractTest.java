package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class MigrationV1ContractTest {

    private static final String MIGRATION = "db/migration/V1__esquema_inicial.sql";

    @Test
    void creaTodasLasTablasDelDominio() throws IOException {
        String sql = migrationSql();

        List.of(
                "instalacion",
                "empresa",
                "tienda",
                "licencia",
                "terminal",
                "rol",
                "permiso",
                "rol_permiso",
                "usuario",
                "sesion",
                "auditoria",
                "configuracion_backup",
                "ejecucion_backup")
            .forEach(tabla -> assertThat(sql).contains("create table " + tabla));
    }

    @Test
    void conservaLosConstraintsCriticos() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
            .contains("check (demo_hasta = creada_en + interval '30 days')")
            .contains("unique (empresa_id, address_normalized_hash)")
            .contains("check (max_windows >= 1)")
            .contains("check (max_pda >= 0)")
            .contains("create unique index ux_licencia_activa_tienda_instalacion")
            .contains("where activa")
            .contains("create unique index ux_terminal_nombre_tienda_ci")
            .contains("lower(nombre)")
            .contains("create unique index ux_terminal_servidor_tienda")
            .contains("where tipo = 'servidor'")
            .contains("unique (tienda_id, nombre)")
            .contains("check (nombre = upper(nombre))")
            .contains("check (codigo = upper(codigo))")
            .contains("check (daily_retention >= 30)")
            .contains("check (monthly_retention >= 72)");
    }

    @Test
    void usaJsonbYVersionOptimistaDondeCorresponde() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
            .contains("domicilio_fiscal jsonb not null")
            .contains("direccion jsonb not null")
            .contains("import_metadata jsonb")
            .contains("datos jsonb")
            .contains("version bigint not null default 0");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
