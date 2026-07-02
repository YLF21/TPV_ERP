package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV27ContractTest {

    private static final String MIGRATION = "db/migration/V27__metadatos_importacion_excel.sql";

    @Test
    void creaTablaParaReferenciasDeProveedorImportadasHastaConfirmacion() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("create table producto_importacion_excel_linea")
                .contains("documento_id uuid not null references documento(id) on delete cascade")
                .contains("producto_id uuid not null references producto(id) on delete cascade")
                .contains("referencia_proveedor varchar(128)")
                .contains("unique (documento_id, producto_id)")
                .contains("ix_producto_importacion_excel_linea_documento");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
