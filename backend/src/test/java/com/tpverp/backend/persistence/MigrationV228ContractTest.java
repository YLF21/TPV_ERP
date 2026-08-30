package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MigrationV228ContractTest {
    @Test
    void definesFencedBackupLeasesWithoutChangingReservedMigrations() throws Exception {
        Path migration = Path.of("src/main/resources/db/migration/V228__backup_durable_leases_restore_journal.sql");
        String sql = Files.readString(migration);
        assertThat(sql).contains("worker_token", "heartbeat_at", "lease_until");
        assertThat(sql).contains("ux_ejecucion_backup_one_active_lease");
        assertThat(sql).contains("backup_restore_finalization", "journal_id uuid PRIMARY KEY", "backup_sha256");
        assertThat(sql).contains("BEFORE UPDATE OR DELETE", "impedir_mutacion_backup_restore_finalization");
        assertThat(sql).contains("'PRE_SIF'");
        assertThat(sql).contains("result = 'EN_CURSO'");
        assertThat(Files.exists(Path.of("src/main/resources/db/migration/V219__producto_numero_serie.sql"))).isTrue();
    }
}
