package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class MigrationV215PostgreSqlTest {

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void enforcesTenantActiveScopeEvidenceAndExecutionTokenOnPostgreSql() throws Exception {
        var url = setting("TPV_ERP_TEST_DB_URL", "jdbc:postgresql://localhost:5432/tpv_erp_test");
        var user = setting("TPV_ERP_TEST_DB_USER", "postgres");
        var password = setting("TPV_ERP_TEST_DB_PASSWORD", "admin");
        assumeTrue(canConnect(url, user, password), "PostgreSQL de pruebas no disponible");
        var schema = "fiscal_integrity_v215_" + UUID.randomUUID().toString().replace("-", "");
        if (!schema.matches("fiscal_integrity_v215_[0-9a-f]{32}")) {
            throw new IllegalStateException("Unexpected test schema identifier");
        }
        var companyId = UUID.randomUUID();
        var otherCompanyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var otherStoreId = UUID.randomUUID();
        var installationId = UUID.randomUUID();
        try {
            FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                    .dataSource(url, user, password)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(true)
                    .target(MigrationVersion.fromVersion("216"))
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                statement.execute("set search_path to " + schema);
                assertLatestFiscalMigrationsAndObjects(statement, schema);
                statement.executeUpdate("""
                        insert into instalacion (id, referencia, public_key, creada_en, demo_hasta)
                        values ('%s', 'V215-TEST', 'public-key', current_timestamp,
                                current_timestamp + interval '30 days')
                        """.formatted(installationId));
                insertCompany(statement, companyId, "B12345674");
                insertCompany(statement, otherCompanyId, "B76543217");
                insertStore(statement, storeId, companyId, "001", "V215-A");
                insertStore(statement, otherStoreId, otherCompanyId, "002", "V215-B");

                var firstJob = UUID.randomUUID();
                insertJob(statement, firstJob, companyId, storeId, installationId,
                        "QUEUED", "null", "[]", "null");

                assertThatThrownBy(() -> insertJob(statement, UUID.randomUUID(), otherCompanyId,
                        otherStoreId, installationId, "RUNNING", "null", "[]", "null"))
                        .isInstanceOf(SQLException.class);

                var token = UUID.randomUUID();
                statement.executeUpdate("""
                        update trabajo_integridad_fiscal
                        set estado = 'RUNNING', token_ejecucion = '%s'
                        where id = '%s'
                        """.formatted(token, firstJob));

                assertThatThrownBy(() -> insertJob(statement, UUID.randomUUID(), companyId,
                        storeId, installationId, "QUEUED", "null", "[]", "null"))
                        .isInstanceOf(SQLException.class);

                assertThatThrownBy(() -> statement.executeUpdate("""
                        update trabajo_integridad_fiscal set token_ejecucion = null
                        where id = '%s'
                        """.formatted(firstJob))).isInstanceOf(SQLException.class);

                assertThatThrownBy(() -> insertJob(statement, UUID.randomUUID(), companyId,
                        otherStoreId, installationId, "FAILED", "null", "[]", "'error'"))
                        .isInstanceOf(SQLException.class);

                var excessiveEvidence = "[\"x\"" + ",\"x\"".repeat(1_000) + "]";
                assertThatThrownBy(() -> insertJob(statement, UUID.randomUUID(), otherCompanyId,
                        otherStoreId, installationId, "FAILED", "null", excessiveEvidence,
                        "'error'"))
                        .isInstanceOf(SQLException.class);

                statement.executeUpdate("""
                        update trabajo_integridad_fiscal
                        set estado = 'COMPLETED', token_ejecucion = null,
                            completado_en = current_timestamp
                        where id = '%s'
                        """.formatted(firstJob));
                insertJob(statement, UUID.randomUUID(), companyId, storeId, installationId,
                        "QUEUED", "null", "[]", "null");

                try (var result = statement.executeQuery("""
                        select count(*) from trabajo_integridad_fiscal
                        where empresa_id = '%s'
                        """.formatted(companyId))) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isEqualTo(2);
                }
            }
        } finally {
            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }

    private static void assertLatestFiscalMigrationsAndObjects(
            java.sql.Statement statement, String schema) throws SQLException {
        try (var history = statement.executeQuery("""
                select version, success
                from %s.flyway_schema_history
                where version in ('211', '213', '214', '215', '216')
                order by version
                """.formatted(schema))) {
            var versions = new java.util.ArrayList<String>();
            while (history.next()) {
                assertThat(history.getBoolean("success")).isTrue();
                versions.add(history.getString("version"));
            }
            assertThat(versions).containsExactly("211", "213", "214", "215", "216");
        }

        var concurrentIndexes = new String[] {
            "ix_registro_fiscal_cursor_scope_seq",
            "ix_registro_fiscal_cursor_issue_date",
            "ix_registro_fiscal_cursor_number_prefix",
            "ix_registro_fiscal_cursor_number_exact",
            "ix_registro_fiscal_relation_record",
            "ix_estado_envio_fiscal_status_updated",
            "ix_registro_fiscal_integrity_scope_seq",
            "ix_registro_fiscal_submission_scope_id",
            "ix_registro_evento_fiscal_cursor_scope_seq",
            "ix_registro_evento_fiscal_summary_scope_time",
            "ix_registro_fiscal_summary_scope_mode_time"
        };
        try (var indexes = statement.executeQuery("""
                select indexname
                from pg_indexes
                where schemaname = '%s'
                """.formatted(schema))) {
            var found = new java.util.HashSet<String>();
            while (indexes.next()) {
                found.add(indexes.getString(1));
            }
            assertThat(found).contains(concurrentIndexes);
        }

        try (var columns = statement.executeQuery("""
                select table_name, column_name
                from information_schema.columns
                where table_schema = '%s'
                  and ((table_name = 'trabajo_integridad_fiscal'
                        and column_name in ('empresa_id', 'tienda_id', 'instalacion_id',
                                            'token_ejecucion', 'evidencia_codigos'))
                    or (table_name = 'requerimiento_fiscal'
                        and column_name in ('periodo_inicio', 'periodo_fin')))
                """.formatted(schema))) {
            var found = new java.util.HashSet<String>();
            while (columns.next()) {
                found.add(columns.getString(1) + "." + columns.getString(2));
            }
            assertThat(found).contains(
                    "trabajo_integridad_fiscal.empresa_id",
                    "trabajo_integridad_fiscal.tienda_id",
                    "trabajo_integridad_fiscal.instalacion_id",
                    "trabajo_integridad_fiscal.token_ejecucion",
                    "trabajo_integridad_fiscal.evidencia_codigos",
                    "requerimiento_fiscal.periodo_inicio",
                    "requerimiento_fiscal.periodo_fin");
        }
    }

    private static void insertCompany(java.sql.Statement statement, UUID companyId, String taxId)
            throws SQLException {
        statement.executeUpdate("""
                insert into empresa (id, tax_id, razon_social, domicilio_fiscal)
                values ('%s', '%s', 'Empresa V215', '{
                  "linea1":"Calle Uno", "codigoPostal":"35001", "ciudad":"Las Palmas",
                  "provincia":"Las Palmas", "pais":"ES"
                }')
                """.formatted(companyId, taxId));
    }

    private static void insertStore(java.sql.Statement statement, UUID storeId, UUID companyId,
            String code, String hash) throws SQLException {
        statement.executeUpdate("""
                insert into tienda (
                  id, empresa_id, nombre, direccion, address_normalized_hash,
                  timezone, moneda, locale, codigo_tienda)
                values ('%s', '%s', 'Tienda V215', '{
                  "linea1":"Calle Uno", "codigoPostal":"35001", "ciudad":"Las Palmas",
                  "provincia":"Las Palmas", "pais":"ES"
                }', '%s', 'Atlantic/Canary', 'EUR', 'es-ES', '%s')
                """.formatted(storeId, companyId, hash, code));
    }

    private static void insertJob(java.sql.Statement statement, UUID id, UUID companyId,
            UUID storeId, UUID installationId, String status, String executionToken,
            String evidence, String error) throws SQLException {
        var completedAt = "COMPLETED".equals(status) ? "current_timestamp" : "null";
        statement.executeUpdate("""
                insert into trabajo_integridad_fiscal (
                  id, empresa_id, tienda_id, instalacion_id, solicitado_por, modo_ejecucion,
                  estado, token_ejecucion, evidencia_codigos, error, creado_en, actualizado_en,
                  completado_en)
                values ('%s', '%s', '%s', '%s', 'admin', 'NO_VERIFACTU',
                  '%s', %s, '%s'::jsonb, %s, current_timestamp, current_timestamp, %s)
                """.formatted(id, companyId, storeId, installationId, status,
                executionToken, evidence.replace("'", "''"), error, completedAt));
    }

    private static boolean canConnect(String url, String user, String password) {
        try (var ignored = DriverManager.getConnection(url, user, password)) {
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private static String setting(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
