package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV63ContractTest {

    private static final String MIGRATION =
            "db/migration/V63__family_subfamily_business_ids.sql";

    @Test
    void addsReadableBusinessIdsToFamiliesAndSubfamilies() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("alter table familia add column family_id varchar(32)")
                .contains("regexp_replace(upper(trim(nombre)), '[^a-z0-9]+', '_', 'g')")
                .contains("alter table familia alter column family_id set not null")
                .contains("ck_familia_family_id_not_blank")
                .contains("ux_familia_family_id_tienda_ci")
                .contains("on familia(tienda_id, upper(trim(family_id)))")
                .contains("alter table subfamilia add column subfamily_id varchar(32)")
                .contains("alter table subfamilia alter column subfamily_id set not null")
                .contains("ck_subfamilia_subfamily_id_not_blank")
                .contains("ux_subfamilia_subfamily_id_familia_ci")
                .contains("on subfamilia(familia_id, upper(trim(subfamily_id)))")
                .contains("row_number() over");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
