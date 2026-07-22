package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SalesInvoiceRectificationMigrationContractTest {

    private static final String MIGRATION = "db/migration/V91__facturas_rectificativas_venta.sql";

    @Test
    void storesOneTypedLinkedFiscalDefinitionPerCorrectiveInvoice() throws IOException {
        var sql = migrationSql();

        assertThat(sql)
                .contains("documento_id uuid primary key references documento(id) on delete cascade")
                .contains("origen_documento_id uuid not null references documento(id)")
                .contains("metodo char(1) not null")
                .contains("check (tipo_fiscal in ('r1', 'r4'))")
                .contains("check (metodo = 'i')")
                .contains("char_length(trim(detalle)) between 10 and 500")
                .contains("ix_factura_rectificacion_venta_origen");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
