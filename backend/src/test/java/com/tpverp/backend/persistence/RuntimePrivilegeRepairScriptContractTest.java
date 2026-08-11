package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RuntimePrivilegeRepairScriptContractTest {

    private static final Path SCRIPT =
            Path.of("scripts", "repair-runtime-privileges.sql");

    @Test
    void repairsCurrentAndFutureDmlPrivilegesWithoutChangingOwnership()
            throws IOException {
        var sql = Files.readString(SCRIPT).toLowerCase();

        assertThat(sql)
                .contains("\\set on_error_stop on")
                .contains("begin;")
                .contains("grant usage on schema")
                .contains("grant select, insert, update, delete on all tables")
                .contains("grant usage, select, update on all sequences")
                .contains("alter default privileges for role")
                .contains("has_table_privilege")
                .contains("has_sequence_privilege")
                .contains("select 1 / 0 as table_privilege_verification_failed")
                .contains("select 1 / 0 as sequence_privilege_verification_failed")
                .contains("commit;")
                .doesNotContain("alter table")
                .doesNotContain("reassign owned")
                .doesNotContain("create role")
                .doesNotContain("password");
    }

    @Test
    void requiresExplicitRuntimeAndMigrationRoles() throws IOException {
        assertThat(Files.readString(SCRIPT).toLowerCase())
                .contains("runtime_role")
                .contains("migration_role")
                .contains("schema_name")
                .contains("select exists (select 1 from pg_roles")
                .contains("select exists (select 1 from pg_namespace");
    }
}
