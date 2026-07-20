package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV77ContractTest {

    private static final String MIGRATION = "db/migration/V77__atribucion_operativa_documentos.sql";

    @Test
    void addsImmutableOriginAndAppendOnlyOperationalEventsWithConservativeBackfill() throws IOException {
        var sql = migrationSql();

        assertThat(sql)
                .contains("add column terminal_origen_id uuid")
                .contains("documento_terminal_origen_tienda_fk")
                .contains("having count(*) = 1")
                .contains("before update of terminal_origen_id on documento")
                .contains("documento.terminal_origen_id es inmutable")
                .contains("create table documento_evento_operativo")
                .contains("'creado'")
                .contains("'confirmado'")
                .contains("'anulado'")
                .contains("'convertido'")
                .contains("'rectificado'")
                .contains("before update or delete on documento_evento_operativo")
                .contains("documento_evento_operativo es append-only");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
