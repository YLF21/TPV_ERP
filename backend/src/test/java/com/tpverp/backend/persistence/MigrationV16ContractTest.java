package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV16ContractTest {

    private static final String MIGRATION =
            "db/migration/V16__members_y_codigos_comerciales.sql";

    @Test
    void migraMembersYCodigosDeNegocio() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("rename column saldo_socio to member_balance")
                .contains("rename to member_balance_movement")
                .contains("code_client")
                .contains("is_member")
                .contains("code_member")
                .contains("num_member")
                .contains("member_since")
                .contains("code_supplier")
                .contains("code_commercial")
                .contains("party_code_counter")
                .contains("rename constraint cliente_saldo_socio_check")
                .contains("replace(constraint_row.conname, 'socio', 'member')")
                .contains("'member'")
                .contains("^c-[0-9]{3}-[0-9]{6}$")
                .contains("^m-[0-9]{3}-[0-9]{6}$")
                .contains("^s-[0-9]{6}$")
                .contains("^co-[0-9]{6}$");
    }

    @Test
    void backfillEsDeterministaPorDocumentoYNombre() throws IOException {
        assertThat(migrationSql())
                .contains("order by upper(trim(c.numero_documento)), c.id")
                .contains("order by upper(trim(p.numero_documento)), p.id")
                .contains("order by upper(trim(c.nombre)), c.id");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
