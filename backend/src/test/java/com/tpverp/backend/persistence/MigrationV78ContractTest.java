package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV78ContractTest {

    private static final String MIGRATION =
            "db/migration/V78__regulariza_admin_global_instalacion.sql";

    @Test
    void promotesOnlyOneUnambiguousLegacyAdminWithoutChangingItsPassword() throws IOException {
        var sql = migrationSql();

        assertThat(sql)
                .contains("not exists")
                .contains("global_admin.tienda_id is null")
                .contains("count(*)")
                .contains("legacy_admin.tienda_id is not null")
                .contains("role_user.rol_id = usuario.rol_id")
                .contains("delete from usuario_tienda")
                .contains("update rol set tienda_id = null")
                .contains("update usuario set tienda_id = null")
                .doesNotContain("password_hash")
                .doesNotContain("must_change_password");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
