package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV238ContractTest {

    @Test
    void backfillsIssuedCodesReplacesLifecycleFunctionsAndProtectsTheLedgers()
            throws IOException {
        String sql = migrationSql().toLowerCase();

        assertThat(occurrences(sql, "create or replace function")).isEqualTo(5);
        assertThat(sql)
                .contains("lock table tienda in exclusive mode")
                .contains("lock table familia, subfamilia")
                .contains("in share row exclusive mode")
                .contains("insert into familia_codigo_reservado (tienda_id, family_code)")
                .contains("select tienda_id, family_code")
                .contains("from familia")
                .contains("insert into subfamilia_codigo_reservado "
                        + "(familia_id, subfamily_suffix)")
                .contains("select familia_id, subfamily_suffix")
                .contains("from subfamilia")
                .contains("create or replace function tpv_reserve_family_code_before_delete()")
                .contains("create or replace function tpv_validate_family_code_insert()")
                .contains("create or replace function tpv_reserve_subfamily_code_before_delete()")
                .contains("create or replace function tpv_validate_subfamily_code()")
                .contains("create or replace function "
                        + "tpv_guard_catalog_code_ledger_append_only()")
                .contains("on conflict (tienda_id, family_code) do nothing")
                .contains("returning family_code")
                .contains("on conflict (familia_id, subfamily_suffix) do nothing")
                .contains("returning subfamily_suffix")
                .contains("if tg_op = 'insert' and not code_claimed")
                .contains("tg_table_schema")
                .contains("create trigger tr_familia_codigo_reservado_append_only")
                .contains("before update or delete on familia_codigo_reservado")
                .contains("create trigger tr_subfamilia_codigo_reservado_append_only")
                .contains("before update or delete on subfamilia_codigo_reservado")
                .contains("create trigger tr_familia_codigo_reservado_no_truncate")
                .contains("before truncate on familia_codigo_reservado")
                .contains("create trigger tr_subfamilia_codigo_reservado_no_truncate")
                .contains("before truncate on subfamilia_codigo_reservado")
                .contains("for each statement execute function "
                        + "tpv_guard_catalog_code_ledger_append_only()")
                .contains("left join familia_codigo_reservado")
                .contains("left join subfamilia_codigo_reservado")
                .doesNotContain("for update")
                .doesNotContain("drop trigger")
                .doesNotContain("alter table");

        assertBefore(sql, "lock table tienda", "lock table familia, subfamilia");
        assertBefore(sql, "lock table familia, subfamilia",
                "insert into familia_codigo_reservado");
        assertBefore(sql, "insert into familia_codigo_reservado", "create or replace function");
        assertBefore(sql, "insert into subfamilia_codigo_reservado", "create or replace function");

        String familyDelete = function(sql, "tpv_reserve_family_code_before_delete",
                "tpv_validate_family_code_insert");
        assertThat(familyDelete).contains("on conflict").doesNotContain("returning");

        String familyInsert = function(sql, "tpv_validate_family_code_insert",
                "tpv_reserve_subfamily_code_before_delete");
        assertThat(familyInsert).contains("returning family_code")
                .doesNotContain("select exists");

        String subfamilyDelete = function(sql, "tpv_reserve_subfamily_code_before_delete",
                "tpv_validate_subfamily_code");
        assertThat(subfamilyDelete).contains("on conflict").doesNotContain("returning");

        String subfamilyInsert = function(sql, "tpv_validate_subfamily_code",
                "tpv_guard_catalog_code_ledger_append_only");
        assertThat(subfamilyInsert).contains("returning subfamily_suffix")
                .contains("if tg_op = 'insert' and not code_claimed")
                .doesNotContain("select exists");

        String ledgerGuard = function(sql, "tpv_guard_catalog_code_ledger_append_only", null);
        assertThat(ledgerGuard).contains("raise exception")
                .contains("append-only");

        assertThat(sql.lastIndexOf("left join familia_codigo_reservado"))
                .isGreaterThan(sql.lastIndexOf("create trigger"));
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V238__serialize_catalog_code_reservations.sql")) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String function(String sql, String name, String nextName) {
        int start = sql.indexOf("create or replace function " + name + "()");
        assertThat(start).isNotNegative();
        int end = nextName == null
                ? sql.length()
                : sql.indexOf("create or replace function " + nextName + "()", start + 1);
        assertThat(end).isGreaterThan(start);
        return sql.substring(start, end);
    }

    private static void assertBefore(String sql, String first, String second) {
        int firstIndex = sql.indexOf(first);
        int secondIndex = sql.indexOf(second);
        assertThat(firstIndex).isNotNegative();
        assertThat(secondIndex).isGreaterThan(firstIndex);
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
}
