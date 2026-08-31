package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MigrationV234MemberTerminologyContractTest {

    @Test
    void renamesDocumentAdjustmentMemberColumnsAtomically() throws Exception {
        Path migration = Path.of(
                "src/main/resources/db/migration/V234__renombrar_campos_documento_ajuste_member.sql");
        String sql = Files.readString(migration).toLowerCase();

        assertThat(sql).contains(
                "alter table documento_ajuste",
                "rename column socio_id to member_id",
                "rename column categoria_socio_id to member_category_id",
                "rename column categoria_socio_nombre to member_category_name")
                .doesNotContain("add column");
    }
}
