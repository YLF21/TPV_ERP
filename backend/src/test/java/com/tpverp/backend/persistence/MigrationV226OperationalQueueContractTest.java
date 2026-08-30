package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MigrationV226OperationalQueueContractTest {

    @Test
    void definesPartialConcurrentIndexesForScopedActiveQueueReads() throws Exception {
        var sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V226__verifactu_operational_queue_read_indexes.sql"));
        var config = Files.readString(Path.of(
                "src/main/resources/db/migration/V226__verifactu_operational_queue_read_indexes.sql.conf"));

        assertThat(sql).contains("create index concurrently")
                .contains("empresa_id, tienda_id, instalacion_id")
                .contains("where modo_fiscal = 'VERIFACTU'")
                .contains("where estado in ('PENDIENTE', 'ENVIANDO', 'ENVIADO', 'RECHAZADO')")
                .doesNotContain("V219");
        assertThat(config).contains("executeInTransaction=false");
    }
}
