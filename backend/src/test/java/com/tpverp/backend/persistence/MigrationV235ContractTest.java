package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV235ContractTest {

    @Test
    void definesStableCodesOrderingAndDeletedCodeReservations() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("add column family_code varchar(3)")
                .contains("add column subfamily_suffix varchar(3)")
                .contains("add column subfamily_code varchar(6)")
                .contains("create table familia_codigo_reservado")
                .contains("create table subfamilia_codigo_reservado")
                .contains("family_code = '000'")
                .contains("trim(family_id) ~ '^[0-9]{3}$'")
                .contains("trim(subfamily.subfamily_id) ~ '^[0-9]{6}$'")
                .contains("generate_series(1, 999)")
                .contains("duplicate_position = 1")
                .contains("tpv_validate_family_code_insert")
                .contains("tpv_validate_subfamily_code")
                .contains("family_code = selected_code")
                .contains("subfamily_suffix = selected_suffix")
                .contains("no quedan familycode disponibles para la tienda")
                .contains("no quedan subfamilysuffix disponibles para la familia")
                .contains("set nombre = 'general'")
                .contains("order by predeterminada desc, lower(nombre), id")
                .contains("subfamily_code = ranked.family_code || ranked.suffix")
                .contains("create unique index ux_familia_family_code_tienda")
                .contains("create unique index ux_subfamilia_suffix_familia")
                .contains("tpv_reserve_family_code_before_delete")
                .contains("tpv_guard_family_code_update")
                .contains("tpv_guard_subfamily_code_update")
                .contains("nombre = 'general' and orden = 0")
                .contains("family_code <> '000' and orden > 0")
                .contains("el alias legado de una familia es inmutable")
                .contains("el alias legado de una subfamilia es inmutable");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V235__stable_family_subfamily_codes.sql")) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
