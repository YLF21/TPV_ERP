package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MigrationV229FiscalSystemVersionReleaseIdentityContractTest {

    @Test
    /**
     * The original identifier was checked against PostgreSQL 18: generated
     * names are truncated to 63 bytes, yielding the exact suffix below.
     */
    void replacesHistoricalPublicVersionUniqueKeyWithoutRewritingRowsOrTrigger() throws Exception {
        var sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V229__fiscal_system_version_release_identity.sql"),
                StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);

        assertThat(sql).contains("drop constraint")
                .contains("version_sistema_fiscal_empresa_id_instalacion_id_version_si_key")
                .contains("add constraint uq_version_sistema_fiscal_release")
                .contains("unique (empresa_id, instalacion_id, version_sistema")
                .contains("numero_instalacion, release_id")
                .doesNotContain("update version_sistema_fiscal")
                .doesNotContain("drop trigger")
                .doesNotContain("create trigger");
    }
}
