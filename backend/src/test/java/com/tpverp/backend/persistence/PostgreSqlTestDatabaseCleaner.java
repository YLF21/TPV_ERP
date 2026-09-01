package com.tpverp.backend.persistence;

import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test-only cleanup for isolated PostgreSQL schemas protected by V238.
 *
 * <p>The production ledger guards remain strict. This helper temporarily disables only the two
 * {@code BEFORE TRUNCATE} triggers, performs the test cleanup, and restores both triggers inside
 * one PostgreSQL statement/transaction.
 */
public final class PostgreSqlTestDatabaseCleaner {

    private static final Pattern ISOLATED_SCHEMA =
            Pattern.compile("^[a-z][a-z0-9_]*_[0-9a-f]{32}$");

    private PostgreSqlTestDatabaseCleaner() {}

    public static void truncateCompanyGraph(JdbcTemplate jdbc, String expectedSchema) {
        truncate(jdbc, expectedSchema, """
                execute format(
                    'truncate table %I.familia_codigo_reservado, %I.subfamilia_codigo_reservado, %I.empresa cascade',
                    tpv_schema, tpv_schema, tpv_schema);
                """);
    }

    public static void truncateInstallationAndCompanyGraphs(
            JdbcTemplate jdbc, String expectedSchema) {
        truncate(jdbc, expectedSchema, """
                execute format(
                    'truncate table %I.familia_codigo_reservado, %I.subfamilia_codigo_reservado, %I.instalacion, %I.empresa cascade',
                    tpv_schema, tpv_schema, tpv_schema, tpv_schema);
                """);
    }

    /** Visible in this package only so V238 can verify restoration after an injected failure. */
    static void truncate(
            JdbcTemplate jdbc, String expectedSchema, String truncateStatement) {
        Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(expectedSchema, "expectedSchema");
        Objects.requireNonNull(truncateStatement, "truncateStatement");
        if (!ISOLATED_SCHEMA.matcher(expectedSchema).matches()) {
            throw new IllegalArgumentException(
                    "El esquema de limpieza debe terminar en un UUID hexadecimal de 32 caracteres");
        }

        String cleanupSql = ("""
                do $tpv_test_cleanup$
                declare
                    tpv_schema text := current_schema();
                    tpv_expected_schema constant text := '__TPV_EXPECTED_SCHEMA__';
                begin
                    if tpv_schema is distinct from tpv_expected_schema then
                        raise exception
                            'La limpieza PostgreSQL esperaba el esquema %, pero current_schema() es %',
                            tpv_expected_schema,
                            coalesce(tpv_schema, '<sin esquema>');
                    end if;
                    execute format(
                        'alter table %I.familia_codigo_reservado disable trigger tr_familia_codigo_reservado_no_truncate',
                        tpv_schema);
                    execute format(
                        'alter table %I.subfamilia_codigo_reservado disable trigger tr_subfamilia_codigo_reservado_no_truncate',
                        tpv_schema);
                """ + truncateStatement + """
                    execute format(
                        'alter table %I.familia_codigo_reservado enable trigger tr_familia_codigo_reservado_no_truncate',
                        tpv_schema);
                    execute format(
                        'alter table %I.subfamilia_codigo_reservado enable trigger tr_subfamilia_codigo_reservado_no_truncate',
                        tpv_schema);
                exception when others then
                    execute format(
                        'alter table %I.familia_codigo_reservado enable trigger tr_familia_codigo_reservado_no_truncate',
                        tpv_schema);
                    execute format(
                        'alter table %I.subfamilia_codigo_reservado enable trigger tr_subfamilia_codigo_reservado_no_truncate',
                        tpv_schema);
                    raise;
                end
                $tpv_test_cleanup$;
                """)
                .replace("__TPV_EXPECTED_SCHEMA__", expectedSchema);
        jdbc.execute(cleanupSql);
    }
}
