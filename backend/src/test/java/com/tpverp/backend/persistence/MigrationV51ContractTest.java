package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV51ContractTest {

    private static final String MIGRATION = "db/migration/V51__reglas_precio_producto.sql";

    @Test
    void createsCompanyScopedTypedPriceRules() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("create table producto_regla_precio")
                .contains("empresa_id uuid not null references empresa(id) on delete cascade")
                .contains("nombre varchar(160) not null")
                .contains("formularios jsonb not null")
                .contains("creado_por uuid not null references usuario(id)")
                .contains("creado_en timestamptz not null")
                .contains("actualizado_en timestamptz not null")
                .contains("version bigint not null default 0")
                .contains("jsonb_typeof(formularios) = 'array'")
                .contains("jsonb_array_length(formularios) > 0")
                .contains("ix_producto_regla_precio_empresa_actualizado");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
