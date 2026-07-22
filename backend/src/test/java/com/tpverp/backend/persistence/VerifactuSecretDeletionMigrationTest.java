package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class VerifactuSecretDeletionMigrationTest {

    private static final String MIGRATION =
            "db/migration/V96__verifactu_secret_deletion_jobs.sql";

    @Test
    void definesDurableIdempotentClaimQueue() throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(sql).contains(
                    "create table verifactu_secret_deletion_job",
                    "constraint uq_verifactu_secret_deletion_path unique (secret_path)",
                    "processing_lease_until timestamptz",
                    "next_attempt_at timestamptz",
                    "where status in ('pendiente', 'procesando')",
                    "where certificate.status = 'eliminado'",
                    "on conflict (secret_path) do nothing");
        }
    }

    @Test
    void backfillsDeletedCertificatesAndSupportsConcurrentSkipLockedClaims() throws Exception {
        String url = setting("TPV_ERP_TEST_DB_URL", "jdbc:postgresql://localhost:5432/tpv_erp_test");
        String user = setting("TPV_ERP_TEST_DB_USER", "postgres");
        String password = setting("TPV_ERP_TEST_DB_PASSWORD", "admin");
        assumeTrue(canConnect(url, user, password), "PostgreSQL de pruebas no disponible");
        String schema = "tpv_erp_v96_" + UUID.randomUUID().toString().replace("-", "");
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID certificateId = UUID.randomUUID();

        try {
            migrate(url, user, password, schema, "95");
            seedDeletedCertificate(
                    url, user, password, schema,
                    companyId, storeId, roleId, userId, certificateId);
            migrate(url, user, password, schema, "96");

            try (Connection connection = DriverManager.getConnection(url, user, password);
                    Statement statement = connection.createStatement()) {
                statement.execute("set search_path to " + schema);
                try (ResultSet result = statement.executeQuery("""
                        select company_id, certificate_id, secret_path, reason, status
                          from verifactu_secret_deletion_job
                        """)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getObject("company_id", UUID.class)).isEqualTo(companyId);
                    assertThat(result.getObject("certificate_id", UUID.class)).isEqualTo(certificateId);
                    assertThat(result.getString("secret_path")).isEqualTo(
                            companyId + "/" + certificateId + "/private-key.dpapi");
                    assertThat(result.getString("reason")).isEqualTo("MIGRATION_RECONCILIATION");
                    assertThat(result.getString("status")).isEqualTo("PENDIENTE");
                    assertThat(result.next()).isFalse();
                }
                statement.executeUpdate("""
                        insert into verifactu_secret_deletion_job (
                            id, company_id, certificate_id, secret_path, reason, status,
                            attempts, next_attempt_at, created_at, version)
                        values ('%s', '%s', null, '%s', 'IMPORT_ROLLBACK', 'PENDIENTE',
                                0, current_timestamp, current_timestamp + interval '1 second', 0)
                        """.formatted(
                        UUID.randomUUID(), companyId,
                        companyId + "/" + UUID.randomUUID() + "/private-key.dpapi"));
            }

            assertConcurrentClaimsDoNotOverlap(url, user, password, schema);
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                    Statement statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }

    private static void assertConcurrentClaimsDoNotOverlap(
            String url, String user, String password, String schema) throws Exception {
        try (Connection first = DriverManager.getConnection(url, user, password);
                Connection second = DriverManager.getConnection(url, user, password)) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            UUID firstId = claimOne(first, schema);
            UUID secondId = claimOne(second, schema);
            assertThat(firstId).isNotNull();
            assertThat(secondId).isNotNull().isNotEqualTo(firstId);
            first.rollback();
            second.rollback();
        }
    }

    private static UUID claimOne(Connection connection, String schema) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema);
            try (ResultSet result = statement.executeQuery("""
                    select id
                      from verifactu_secret_deletion_job
                     where status = 'PENDIENTE' and next_attempt_at <= current_timestamp
                     order by created_at, id
                     for update skip locked
                     limit 1
                    """)) {
                return result.next() ? result.getObject(1, UUID.class) : null;
            }
        }
    }

    private static void seedDeletedCertificate(
            String url, String user, String password, String schema,
            UUID companyId, UUID storeId, UUID roleId, UUID userId, UUID certificateId)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema);
            statement.executeUpdate("""
                    insert into empresa (id, tax_id, razon_social, domicilio_fiscal)
                    values ('%s', 'B94000001', 'Empresa V94',
                            '{"linea1":"Uno","ciudad":"Las Palmas","codigoPostal":"35001","provincia":"Las Palmas","pais":"ES"}')
                    """.formatted(companyId));
            statement.executeUpdate("""
                    insert into tienda (id, empresa_id, nombre, direccion, address_normalized_hash,
                                        timezone, moneda, locale, codigo_tienda)
                    values ('%s', '%s', 'Tienda V94',
                            '{"linea1":"Uno","ciudad":"Las Palmas","codigoPostal":"35001","provincia":"Las Palmas","pais":"ES"}',
                            'V94-HASH', 'Atlantic/Canary', 'EUR', 'es-ES', '094')
                    """.formatted(storeId, companyId));
            statement.executeUpdate("""
                    insert into rol (id, tienda_id, nombre, protegido)
                    values ('%s', '%s', 'GESTOR_V94', false)
                    """.formatted(roleId, storeId));
            statement.executeUpdate("""
                    insert into usuario (
                        id, tienda_id, nombre, password_hash, rol_id, protegido, activo,
                        user_id, user_name, must_change_password)
                    values ('%s', '%s', 'GESTOR_V94', 'hash', '%s', false, true,
                            'E-940001', 'Gestor V94', false)
                    """.formatted(userId, storeId, roleId));
            statement.executeUpdate("""
                    insert into certificado_verifactu (
                        id, empresa_id, status, subject, issuer, serial_number, tax_id,
                        valid_from, valid_until, fingerprint, public_chain, secret_path,
                        imported_at, imported_by, deleted_at, deleted_by, version)
                    values ('%s', '%s', 'ELIMINADO', 'CN=Empresa V94', 'CN=AC', '94001',
                            'B94000001', current_timestamp - interval '2 years',
                            current_timestamp + interval '1 year', '%s', decode('01', 'hex'), null,
                            current_timestamp - interval '1 year', '%s', current_timestamp, '%s', 0)
                    """.formatted(certificateId, companyId, "A".repeat(64), userId, userId));
        }
    }

    private static void migrate(
            String url, String user, String password, String schema, String target) {
        Flyway.configure()
                .dataSource(url, user, password)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .target(target)
                .load()
                .migrate();
    }

    private static boolean canConnect(String url, String user, String password) {
        try (Connection ignored = DriverManager.getConnection(url, user, password)) {
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private static String setting(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
