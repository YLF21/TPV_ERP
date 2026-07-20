package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MigrationV85ReconciliationContractTest {

    @Test
    void reconcilesReassignedOperationalAttributionMigrationsIdempotently() throws Exception {
        var sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V85__reconcile_document_operational_attribution.sql"))
                .toLowerCase();

        assertThat(sql).contains(
                "add column if not exists terminal_origen_id uuid",
                "create table if not exists documento_evento_operativo",
                "create index if not exists ix_documento_tienda_terminal_fecha",
                "documento_terminal_origen_inmutable",
                "documento_evento_operativo_append_only",
                "event.datos @> '{\"migrado\": true}'::jsonb");
    }
}
