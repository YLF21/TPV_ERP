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

/** Integration contract for the V235 backfill and database-side invariants. */
class MigrationV235PostgreSqlTest {

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void preservesValidCodesAssignsCollisionsDeterministicallyAndProtectsReservations()
            throws Exception {
        String url = setting("TPV_ERP_TEST_DB_URL",
                "jdbc:postgresql://localhost:5432/tpv_erp_test");
        String user = setting("TPV_ERP_TEST_DB_USER", "tpv_erp_test");
        String password = setting("TPV_ERP_TEST_DB_PASSWORD", "admin");
        assumeTrue(canConnect(url, user, password), "PostgreSQL de pruebas no disponible");
        String schema = "catalog_codes_v235_" + UUID.randomUUID().toString().replace("-", "");
        UUID companyId = UUID.randomUUID();
        UUID otherCompanyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID otherStoreId = UUID.randomUUID();
        UUID generalId = id("00000000-0000-0000-0000-000000000001");
        UUID preservedId = id("00000000-0000-0000-0000-000000000007");
        UUID collisionId = id("00000000-0000-0000-0000-000000000008");
        UUID alphaId = id("00000000-0000-0000-0000-000000000009");
        UUID otherFamilyId = id("00000000-0000-0000-0000-000000000017");
        UUID otherGeneralId = id("00000000-0000-0000-0000-000000000018");
        UUID subPreservedId = id("00000000-0000-0000-0000-000000000101");
        UUID subCollisionId = id("00000000-0000-0000-0000-000000000102");
        UUID subSixDigitId = id("00000000-0000-0000-0000-000000000103");
        UUID subAlphaId = id("00000000-0000-0000-0000-000000000104");
        try {
            migrate(url, user, password, schema, "234");
            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                statement.execute("set search_path to " + schema);
                insertCompany(statement, companyId, "B23500001", "Empresa V235 A");
                insertCompany(statement, otherCompanyId, "B23500002", "Empresa V235 B");
                insertStore(statement, storeId, companyId, "235");
                insertStore(statement, otherStoreId, otherCompanyId, "236");

                // V60 made aliases unique. Dropping only those legacy indexes
                // lets this fixture exercise V235's deterministic collision rule.
                statement.execute("drop index " + schema + ".ux_familia_family_id_tienda_ci");
                statement.execute("drop index " + schema + ".ux_subfamilia_subfamily_id_familia_ci");
                insertFamily(statement, generalId, storeId, "GENERAL", true, "GENERAL");
                insertFamily(statement, preservedId, storeId, "Bebidas", false, "007");
                insertFamily(statement, collisionId, storeId, "Cocina", false, "007");
                insertFamily(statement, alphaId, storeId, "Alfa", false, "ALFA");
                insertFamily(statement, otherGeneralId, otherStoreId, "GENERAL", true, "GENERAL");
                insertFamily(statement, otherFamilyId, otherStoreId, "Bebidas", false, "007");
                insertSubfamily(statement, subPreservedId, preservedId, "Cafe", "005");
                insertSubfamily(statement, subCollisionId, preservedId, "Te", "005");
                insertSubfamily(statement, subSixDigitId, preservedId, "Refrescos", "007006");
                insertSubfamily(statement, subAlphaId, preservedId, "Zumos", "ALFA");
            }

            // Keep this integration test scoped to V235; V236 has its own
            // migration/invariant test and deliberately removes orden.
            migrate(url, user, password, schema, "235");
            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                statement.execute("set search_path to " + schema);
                assertFamilies(statement, storeId, generalId, preservedId, collisionId, alphaId);
                assertFamilyCode(statement, otherFamilyId, "007", 1);
                assertSubfamilies(statement, preservedId, subPreservedId, subCollisionId,
                        subSixDigitId, subAlphaId);

                assertThatThrownBy(() -> statement.executeUpdate(
                        "delete from familia where id = '" + generalId + "'"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.executeUpdate(
                        "update familia set nombre = 'Otra' where id = '" + generalId + "'"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.executeUpdate(
                        "update familia set nombre = ' general ' where id = '" + generalId + "'"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.executeUpdate(
                        "update familia set family_id = 'GENERAL-NUEVO' where id = '" + generalId + "'"))
                        .isInstanceOf(SQLException.class);
                UUID autoOrderedFamilyId = UUID.randomUUID();
                insertFamilyWithOrder(statement, autoOrderedFamilyId, storeId,
                        "Orden automatico", "099", 0);
                assertFamilyOrderPositive(statement, autoOrderedFamilyId);

                statement.executeUpdate("delete from familia where id = '" + alphaId + "'");
                assertThatThrownBy(() -> insertFamilyWithCode(statement, UUID.randomUUID(), storeId,
                        "Nuevo", false, "001")).isInstanceOf(SQLException.class);

                statement.executeUpdate("delete from subfamilia where id = '" + subCollisionId + "'");
                try (var reservations = statement.executeQuery("select subfamily_suffix from "
                        + "subfamilia_codigo_reservado where familia_id = '" + preservedId
                        + "' and subfamily_suffix = '001'")) {
                    assertThat(reservations.next()).isTrue();
                }
                assertThatThrownBy(() -> insertSubfamilyWithCode(statement, UUID.randomUUID(), preservedId,
                        "Reusada", "001", "007001")).isInstanceOf(SQLException.class);

                assertThatThrownBy(() -> statement.executeUpdate(
                        "update familia set family_code = '009' where id = '" + preservedId + "'"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.executeUpdate(
                        "update subfamilia set subfamily_suffix = '009', subfamily_code = '007009' "
                                + "where id = '" + subSixDigitId + "'"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.executeUpdate(
                        "update subfamilia set subfamily_id = 'ALIAS-NUEVO' where id = '"
                                + subSixDigitId + "'"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> insertSubfamilyWithCode(statement, UUID.randomUUID(), preservedId,
                        "Incoherente", "009", "999009")).isInstanceOf(SQLException.class);
            }
        } finally {
            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }

    private static void assertFamilies(java.sql.Statement statement, UUID storeId, UUID generalId,
            UUID preservedId, UUID collisionId, UUID alphaId) throws SQLException {
        try (var rows = statement.executeQuery("select id, family_code, orden from familia "
                + "where tienda_id = '" + storeId + "' order by orden")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getObject("id", UUID.class)).isEqualTo(generalId);
            assertThat(rows.getString("family_code")).isEqualTo("000");
            assertThat(rows.getInt("orden")).isZero();
            assertThat(rows.next()).isTrue();
            assertThat(rows.getObject("id", UUID.class)).isEqualTo(alphaId);
            assertThat(rows.getString("family_code")).isEqualTo("001");
            assertThat(rows.getInt("orden")).isEqualTo(1);
            assertThat(rows.next()).isTrue();
            assertThat(rows.getObject("id", UUID.class)).isEqualTo(preservedId);
            assertThat(rows.getString("family_code")).isEqualTo("007");
            assertThat(rows.getInt("orden")).isEqualTo(2);
            assertThat(rows.next()).isTrue();
            assertThat(rows.getObject("id", UUID.class)).isEqualTo(collisionId);
            assertThat(rows.getString("family_code")).isEqualTo("002");
            assertThat(rows.getInt("orden")).isEqualTo(3);
            assertThat(rows.next()).isFalse();
        }
    }

    private static void assertFamilyCode(java.sql.Statement statement, UUID familyId, String expected,
            int expectedOrder)
            throws SQLException {
        try (var rows = statement.executeQuery("select family_code, orden from familia where id = '"
                + familyId + "'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo(expected);
            assertThat(rows.getInt(2)).isEqualTo(expectedOrder);
        }
    }

    private static void assertFamilyOrderPositive(java.sql.Statement statement, UUID familyId)
            throws SQLException {
        try (var rows = statement.executeQuery("select orden from familia where id = '"
                + familyId + "'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isPositive();
        }
    }

    private static void assertSubfamilies(java.sql.Statement statement, UUID familyId,
            UUID preservedId, UUID collisionId, UUID sixDigitId, UUID alphaId) throws SQLException {
        try (var rows = statement.executeQuery("select id, subfamily_suffix, subfamily_code from subfamilia "
                + "where familia_id = '" + familyId + "' order by subfamily_code")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getObject("id", UUID.class)).isEqualTo(collisionId);
            assertThat(rows.getString("subfamily_suffix")).isEqualTo("001");
            assertThat(rows.getString("subfamily_code")).isEqualTo("007001");
            assertThat(rows.next()).isTrue();
            assertThat(rows.getObject("id", UUID.class)).isEqualTo(alphaId);
            assertThat(rows.getString("subfamily_suffix")).isEqualTo("002");
            assertThat(rows.getString("subfamily_code")).isEqualTo("007002");
            assertThat(rows.next()).isTrue();
            assertThat(rows.getObject("id", UUID.class)).isEqualTo(preservedId);
            assertThat(rows.getString("subfamily_suffix")).isEqualTo("005");
            assertThat(rows.getString("subfamily_code")).isEqualTo("007005");
            assertThat(rows.next()).isTrue();
            assertThat(rows.getObject("id", UUID.class)).isEqualTo(sixDigitId);
            assertThat(rows.getString("subfamily_suffix")).isEqualTo("006");
            assertThat(rows.getString("subfamily_code")).isEqualTo("007006");
            assertThat(rows.next()).isFalse();
        }
    }

    private static void insertCompany(java.sql.Statement statement, UUID id, String taxId, String name)
            throws SQLException {
        statement.executeUpdate(("insert into empresa (id, tax_id, razon_social, domicilio_fiscal) "
                + "values ('%s', '%s', '%s', '{\"linea1\":\"Test\",\"ciudad\":\"Las Palmas\","
                + "\"codigoPostal\":\"35001\",\"provincia\":\"Las Palmas\",\"pais\":\"ES\"}')")
                .formatted(id, taxId, name));
    }

    private static void insertStore(java.sql.Statement statement, UUID id, UUID companyId, String code)
            throws SQLException {
        statement.executeUpdate(("insert into tienda (id, empresa_id, codigo_tienda, nombre, direccion, "
                + "address_normalized_hash, timezone, moneda, locale) values ('%s', '%s', '%s', '%s', "
                + "'{\"linea1\":\"Test\",\"ciudad\":\"Las Palmas\",\"codigoPostal\":\"35001\","
                + "\"provincia\":\"Las Palmas\",\"pais\":\"ES\"}', '%s', 'Atlantic/Canary', 'EUR', 'es-ES')")
                .formatted(id, companyId, code, "Tienda " + code, "hash-" + code));
    }

    private static void insertFamily(java.sql.Statement statement, UUID id, UUID storeId, String name,
            boolean defaultFamily, String alias) throws SQLException {
        statement.executeUpdate(("insert into familia (id, tienda_id, family_id, nombre, predeterminada) "
                + "values ('%s', '%s', '%s', '%s', %s)")
                .formatted(id, storeId, alias, name, defaultFamily));
    }

    private static void insertFamilyWithCode(java.sql.Statement statement, UUID id, UUID storeId,
            String name, boolean defaultFamily, String code) throws SQLException {
        statement.executeUpdate(("insert into familia (id, tienda_id, family_id, family_code, nombre, "
                + "predeterminada, orden) values ('%s', '%s', '%s', '%s', '%s', %s, 1)")
                .formatted(id, storeId, code, code, name, defaultFamily));
    }

    private static void insertFamilyWithOrder(java.sql.Statement statement, UUID id, UUID storeId,
            String name, String code, int order) throws SQLException {
        statement.executeUpdate(("insert into familia (id, tienda_id, family_id, family_code, nombre, "
                + "predeterminada, orden) values ('%s', '%s', '%s', '%s', '%s', false, %s)")
                .formatted(id, storeId, code, code, name, order));
    }

    private static void insertSubfamily(java.sql.Statement statement, UUID id, UUID familyId,
            String name, String alias) throws SQLException {
        statement.executeUpdate(("insert into subfamilia (id, familia_id, subfamily_id, nombre) "
                + "values ('%s', '%s', '%s', '%s')").formatted(id, familyId, alias, name));
    }

    private static void insertSubfamilyWithCode(java.sql.Statement statement, UUID id, UUID familyId,
            String name, String suffix, String code) throws SQLException {
        statement.executeUpdate(("insert into subfamilia (id, familia_id, subfamily_id, nombre, "
                + "subfamily_suffix, subfamily_code) values ('%s', '%s', '%s', '%s', '%s', '%s')")
                .formatted(id, familyId, code, name, suffix, code));
    }

    private static void migrate(String url, String user, String password, String schema, String target) {
        var configuration = FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                .dataSource(url, user, password)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true);
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target));
        }
        configuration.load().migrate();
    }

    private static UUID id(String value) {
        return UUID.fromString(value);
    }

    private static boolean canConnect(String url, String user, String password) {
        try (var ignored = DriverManager.getConnection(url, user, password)) {
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
