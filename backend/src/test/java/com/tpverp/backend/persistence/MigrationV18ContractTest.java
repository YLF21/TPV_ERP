package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV18ContractTest {

    private static final String MIGRATION =
            "db/migration/V18__renombrar_codigos_party_a_ids.sql";

    @Test
    void renombraCodigosDeNegocioAIds() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("rename column code_client to client_id")
                .contains("rename column code_member to member_id")
                .contains("rename column code_supplier to supplier_id")
                .contains("rename column code_commercial to commercial_id")
                .contains("rename constraint ck_cliente_code_client to ck_cliente_client_id")
                .contains("rename constraint ux_cliente_empresa_code_client to ux_cliente_empresa_client_id");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
