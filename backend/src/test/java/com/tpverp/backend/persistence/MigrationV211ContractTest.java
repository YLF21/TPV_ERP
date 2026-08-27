package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class MigrationV211ContractTest {

    @Test
    void usesRetrySafeConcurrentBtreeIndexesAndFlywayNonTransactionalSidecar() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V211__fiscal_cursor_read_indexes.sql")) {
            assertThat(input).isNotNull();
            var sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
            assertThat(sql).contains("drop index concurrently if exists ix_registro_fiscal_cursor_scope_seq")
                    .contains("create index concurrently ix_registro_fiscal_cursor_scope_seq")
                    .contains("drop index concurrently if exists ix_registro_fiscal_cursor_issue_date")
                    .contains("create index concurrently ix_registro_fiscal_cursor_issue_date")
                    .contains("drop index concurrently if exists ix_registro_fiscal_cursor_number_prefix")
                    .contains("create index concurrently ix_registro_fiscal_cursor_number_prefix")
                    .contains("drop index concurrently if exists ix_registro_fiscal_cursor_number_exact")
                    .contains("create index concurrently ix_registro_fiscal_cursor_number_exact")
                    .contains("lower(serie_numero) text_pattern_ops")
                    .contains("drop index concurrently if exists ix_registro_fiscal_relation_record")
                    .contains("create index concurrently ix_registro_fiscal_relation_record")
                    .doesNotContain("pg_trgm", "gin_trgm_ops", "create extension");
        }
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V211__fiscal_cursor_read_indexes.sql.conf")) {
            assertThat(input).isNotNull();
            assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8).trim())
                    .isEqualTo("executeInTransaction=false");
        }
    }
}
