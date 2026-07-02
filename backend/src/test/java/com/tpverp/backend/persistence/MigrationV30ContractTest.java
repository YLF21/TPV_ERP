package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV30ContractTest {

    private static final String MIGRATION = "db/migration/V30__miembros_separados.sql";

    @Test
    void separaMiembrosDeClientesYConservaDatosExistentes() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("create table miembro")
                .contains("cliente_id uuid not null references cliente(id)")
                .contains("member_id varchar(12)")
                .contains("num_member varchar(255)")
                .contains("member_since date")
                .contains("member_balance numeric(19,2) not null")
                .contains("active boolean not null")
                .contains("insert into miembro")
                .contains("from cliente")
                .contains("alter table cliente drop column is_member")
                .contains("alter table cliente drop column member_id")
                .contains("alter table cliente drop column num_member")
                .contains("alter table cliente drop column member_since")
                .contains("alter table cliente drop column member_balance");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
