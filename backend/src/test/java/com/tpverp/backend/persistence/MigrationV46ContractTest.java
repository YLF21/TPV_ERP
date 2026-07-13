package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV46ContractTest {

    private static final String MIGRATION = "db/migration/V46__edicion_masiva_productos.sql";

    @Test
    void createsPersistentBulkEditsAndComments() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("create table producto_edicion_masiva")
                .contains("tienda_id uuid not null references tienda(id)")
                .contains("creado_por uuid not null references usuario(id)")
                .contains("contenido jsonb not null")
                .contains("serie_id uuid not null")
                .contains("numero_version integer not null")
                .contains("version_anterior_id uuid references producto_edicion_masiva(id) on delete set null")
                .contains("ux_producto_edicion_masiva_version")
                .contains("estado in ('pending', 'applied')")
                .contains("create table producto_edicion_masiva_comentario")
                .contains("on delete cascade");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
