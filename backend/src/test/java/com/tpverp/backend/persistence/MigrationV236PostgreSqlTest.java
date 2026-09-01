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

/** PostgreSQL integration coverage for V236 invariants and fail-fast preflight. */
class MigrationV236PostgreSqlTest {
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void removesOrderRepairsFamilyAndEnforcesBothCompositeFks() throws Exception {
        String url = setting("TPV_ERP_TEST_DB_URL", "jdbc:postgresql://localhost:5432/tpv_erp_test");
        String user = setting("TPV_ERP_TEST_DB_USER", "tpv_erp_test");
        String password = setting("TPV_ERP_TEST_DB_PASSWORD", "admin");
        assumeTrue(canConnect(url, user, password), "PostgreSQL de pruebas no disponible");
        String schema = "catalog_codes_v236_" + UUID.randomUUID().toString().replace("-", "");
        UUID company = UUID.randomUUID(), company2 = UUID.randomUUID();
        UUID store = UUID.randomUUID(), store2 = UUID.randomUUID();
        UUID general = UUID.randomUUID(), family = UUID.randomUUID(), sameStoreFamily = UUID.randomUUID(), otherFamily = UUID.randomUUID();
        UUID subfamily = UUID.randomUUID(), wrongSubfamily = UUID.randomUUID(), tax = UUID.randomUUID(), product = UUID.randomUUID();
        try {
            migrate(url, user, password, schema, "235");
            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                statement.execute("set search_path to " + schema);
                insertCompany(statement, company, "B23600001");
                insertCompany(statement, company2, "B23600002");
                insertStore(statement, store, company, "361");
                insertStore(statement, store2, company2, "362");
                insertFamily(statement, general, store, "GENERAL", true, "000", "GENERAL", 0);
                insertFamily(statement, family, store, "Bebidas", false, "007", "007", 1);
                insertFamily(statement, sameStoreFamily, store, "Otra local", false, "008", "008", 2);
                insertFamily(statement, otherFamily, store2, "Otra", false, "008", "008", 1);
                insertSubfamily(statement, subfamily, family, "Cafe", "007012", "012", 1);
                insertSubfamily(statement, wrongSubfamily, sameStoreFamily, "Te", "008013", "013", 1);
                insertTax(statement, tax, store);
                // Deliberately incoherent legacy classification; V236 must use
                // the subfamily parent before adding the FK.
                insertProduct(statement, product, store, general, subfamily, tax);
            }

            migrate(url, user, password, schema, "236");
            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                statement.execute("set search_path to " + schema);
                assertThat(columnExists(statement, "familia", "orden")).isFalse();
                assertThat(columnExists(statement, "subfamilia", "orden")).isFalse();
                assertThat(indexExists(statement, "ix_familia_tienda_orden")).isFalse();
                assertThat(indexExists(statement, "ix_subfamilia_familia_orden")).isFalse();
                assertThat(constraintExists(statement, "uq_subfamilia_familia_id")).isTrue();
                assertThat(constraintExists(statement, "uq_familia_tienda_id")).isTrue();
                assertThat(constraintExists(statement, "fk_producto_tienda_familia")).isTrue();
                assertThat(constraintExists(statement, "fk_producto_familia_subfamilia")).isTrue();
                assertThat(scalar(statement, "select familia_id from producto where id = '" + product + "'"))
                        .isEqualTo(family.toString());
                assertThat(scalar(statement, "select family_id from familia where id = '" + family + "'"))
                        .isEqualTo("007");
                assertThat(scalar(statement, "select subfamily_id from subfamilia where id = '" + subfamily + "'"))
                        .isEqualTo("007012");

                UUID foreignProduct = UUID.randomUUID();
                assertThatThrownBy(() -> insertProduct(statement, foreignProduct, store, otherFamily, null, tax))
                        .isInstanceOf(SQLException.class);
                UUID directProduct = UUID.randomUUID();
                insertProduct(statement, directProduct, store, sameStoreFamily, null, tax);
                assertThat(scalar(statement,
                        "select subfamilia_id from producto where id = '" + directProduct + "'"))
                        .isNull();
                assertThatThrownBy(() -> statement.executeUpdate(
                        "update producto set subfamilia_id = '" + wrongSubfamily + "' where id = '" + product + "'"))
                        .isInstanceOf(SQLException.class);

                UUID generatedFamily = UUID.randomUUID();
                insertFamilyWithoutOrder(statement, generatedFamily, store, "Nueva", "NUEVA");
                assertThat(scalar(statement, "select family_code from familia where id = '" + generatedFamily + "'"))
                        .matches("[0-9]{3}");
                UUID generatedSubfamily = UUID.randomUUID();
                assertThatThrownBy(() -> insertSubfamilyWithoutOrder(
                        statement, generatedSubfamily, general, "No permitida", "NO_PERMITIDA"))
                        .isInstanceOf(SQLException.class);

                assertThat(scalar(statement,
                        "select count(*) from flyway_schema_history where version = '236'"))
                        .isEqualTo("1");
            }
        } finally {
            dropSchema(url, user, password, schema);
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void preflightLeavesV235OrderAndTriggersUntouchedWhenGeneralHasChildren() throws Exception {
        String url = setting("TPV_ERP_TEST_DB_URL", "jdbc:postgresql://localhost:5432/tpv_erp_test");
        String user = setting("TPV_ERP_TEST_DB_USER", "tpv_erp_test");
        String password = setting("TPV_ERP_TEST_DB_PASSWORD", "admin");
        assumeTrue(canConnect(url, user, password), "PostgreSQL de pruebas no disponible");
        String schema = "catalog_codes_v236_guard_" + UUID.randomUUID().toString().replace("-", "");
        UUID company = UUID.randomUUID(), store = UUID.randomUUID(), general = UUID.randomUUID(), child = UUID.randomUUID();
        try {
            migrate(url, user, password, schema, "235");
            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                statement.execute("set search_path to " + schema);
                insertCompany(statement, company, "B23600003");
                insertStore(statement, store, company, "363");
                insertFamily(statement, general, store, "GENERAL", true, "000", "GENERAL", 0);
                insertSubfamily(statement, child, general, "Ilegal", "000001", "001", 1);
            }
            assertThatThrownBy(() -> migrate(url, user, password, schema, "236"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("GENERAL");
            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                statement.execute("set search_path to " + schema);
                assertThat(columnExists(statement, "familia", "orden")).isTrue();
                assertThat(triggerExists(statement, "tr_familia_guard_code_update")).isTrue();
                assertThat(indexExists(statement, "ix_familia_tienda_orden")).isTrue();
                assertThat(indexExists(statement, "ix_subfamilia_familia_orden")).isTrue();
                assertThat(constraintExists(statement, "fk_producto_familia_subfamilia")).isFalse();
                assertThat(scalar(statement,
                        "select count(*) from flyway_schema_history where version = '236'"))
                        .isEqualTo("0");
                assertThat(scalar(statement, "select count(*) from subfamilia where familia_id = '" + general + "'"))
                        .isEqualTo("1");
            }
        } finally {
            dropSchema(url, user, password, schema);
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void crossStoreSubfamilyPreflightFailsBeforeAnyV236Mutation() throws Exception {
        String url = setting("TPV_ERP_TEST_DB_URL", "jdbc:postgresql://localhost:5432/tpv_erp_test");
        String user = setting("TPV_ERP_TEST_DB_USER", "tpv_erp_test");
        String password = setting("TPV_ERP_TEST_DB_PASSWORD", "admin");
        assumeTrue(canConnect(url, user, password), "PostgreSQL de pruebas no disponible");
        String schema = "catalog_codes_v236_cross_store_" + UUID.randomUUID().toString().replace("-", "");
        UUID company = UUID.randomUUID(), company2 = UUID.randomUUID();
        UUID store = UUID.randomUUID(), store2 = UUID.randomUUID();
        UUID family = UUID.randomUUID(), otherFamily = UUID.randomUUID(), child = UUID.randomUUID();
        UUID tax = UUID.randomUUID(), product = UUID.randomUUID();
        try {
            migrate(url, user, password, schema, "235");
            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                statement.execute("set search_path to " + schema);
                insertCompany(statement, company, "B23600004");
                insertCompany(statement, company2, "B23600005");
                insertStore(statement, store, company, "364");
                insertStore(statement, store2, company2, "365");
                insertFamily(statement, family, store, "Bebidas", false, "007", "007", 1);
                insertFamily(statement, otherFamily, store2, "Otra", false, "007", "007", 1);
                insertSubfamily(statement, child, otherFamily, "Cafe", "007012", "012", 1);
                insertTax(statement, tax, store);
                insertProduct(statement, product, store, family, child, tax);
            }
            assertThatThrownBy(() -> migrate(url, user, password, schema, "236"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("subfamilia");
            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                statement.execute("set search_path to " + schema);
                assertThat(columnExists(statement, "familia", "orden")).isTrue();
                assertThat(indexExists(statement, "ix_familia_tienda_orden")).isTrue();
                assertThat(scalar(statement,
                        "select count(*) from flyway_schema_history where version = '236'"))
                        .isEqualTo("0");
            }
        } finally {
            dropSchema(url, user, password, schema);
        }
    }

    private static void insertCompany(java.sql.Statement s, UUID id, String taxId) throws SQLException {
        s.executeUpdate(("insert into empresa (id,tax_id,razon_social,domicilio_fiscal) values ('%s','%s','Empresa','{" +
                "\"linea1\":\"Test\",\"ciudad\":\"Las Palmas\",\"codigoPostal\":\"35001\",\"provincia\":\"Las Palmas\",\"pais\":\"ES\"}')")
                .formatted(id, taxId));
    }

    private static void insertStore(java.sql.Statement s, UUID id, UUID company, String code) throws SQLException {
        s.executeUpdate(("insert into tienda (id,empresa_id,codigo_tienda,nombre,direccion,address_normalized_hash,timezone,moneda,locale) " +
                "values ('%s','%s','%s','Tienda','{\"linea1\":\"Test\",\"ciudad\":\"Las Palmas\",\"codigoPostal\":\"35001\",\"provincia\":\"Las Palmas\",\"pais\":\"ES\"}','hash-%s','Atlantic/Canary','EUR','es-ES')")
                .formatted(id, company, code, code));
    }

    private static void insertFamily(java.sql.Statement s, UUID id, UUID store, String name, boolean general,
            String code, String alias, int order) throws SQLException {
        s.executeUpdate(("insert into familia (id,tienda_id,family_id,family_code,nombre,predeterminada,orden) values ('%s','%s','%s','%s','%s',%s,%s)")
                .formatted(id, store, alias, code, name, general, order));
    }

    private static void insertSubfamily(java.sql.Statement s, UUID id, UUID family, String name, String code,
            String suffix, int order) throws SQLException {
        s.executeUpdate(("insert into subfamilia (id,familia_id,subfamily_id,subfamily_suffix,subfamily_code,nombre,orden) values ('%s','%s','%s','%s','%s','%s',%s)")
                .formatted(id, family, code, suffix, code, name, order));
    }

    private static void insertFamilyWithoutOrder(java.sql.Statement s, UUID id, UUID store, String name,
            String alias) throws SQLException {
        s.executeUpdate(("insert into familia (id,tienda_id,family_id,nombre,predeterminada) "
                + "values ('%s','%s','%s','%s',false)").formatted(id, store, alias, name));
    }

    private static void insertSubfamilyWithoutOrder(java.sql.Statement s, UUID id, UUID family, String name,
            String alias) throws SQLException {
        s.executeUpdate(("insert into subfamilia (id,familia_id,subfamily_id,nombre) "
                + "values ('%s','%s','%s','%s')").formatted(id, family, alias, name));
    }

    private static void insertTax(java.sql.Statement s, UUID id, UUID store) throws SQLException {
        s.executeUpdate(("insert into impuesto_tienda (id,tienda_id,porcentaje) values ('%s','%s',21)")
                .formatted(id, store));
    }

    private static void insertProduct(java.sql.Statement s, UUID id, UUID store, UUID family, UUID subfamily, UUID tax)
            throws SQLException {
        s.executeUpdate(("insert into producto (id,tienda_id,familia_id,subfamilia_id,impuesto_id,nombre) values ('%s','%s','%s',%s,'%s','Producto')")
                .formatted(id, store, family, subfamily == null ? "null" : "'" + subfamily + "'", tax));
    }

    private static boolean columnExists(java.sql.Statement s, String table, String column) throws SQLException {
        try (var rows = s.executeQuery("select 1 from information_schema.columns where table_schema=current_schema() and table_name='" + table + "' and column_name='" + column + "'")) {
            return rows.next();
        }
    }

    private static boolean triggerExists(java.sql.Statement s, String trigger) throws SQLException {
        try (var rows = s.executeQuery("select 1 from pg_trigger where tgname='" + trigger + "' and not tgisinternal")) {
            return rows.next();
        }
    }

    private static boolean indexExists(java.sql.Statement s, String index) throws SQLException {
        try (var rows = s.executeQuery("select 1 from pg_class where relkind = 'i' and relname='" + index + "'")) {
            return rows.next();
        }
    }

    private static boolean constraintExists(java.sql.Statement s, String constraint) throws SQLException {
        try (var rows = s.executeQuery("select 1 from pg_constraint where conname='" + constraint + "'")) {
            return rows.next();
        }
    }

    private static String scalar(java.sql.Statement s, String sql) throws SQLException {
        try (var rows = s.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static void migrate(String url, String user, String password, String schema, String target) {
        var configuration = FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                .dataSource(url, user, password).schemas(schema).defaultSchema(schema).createSchemas(true);
        if (target != null) configuration.target(MigrationVersion.fromVersion(target));
        configuration.load().migrate();
    }

    private static void dropSchema(String url, String user, String password, String schema) {
        try (var connection = DriverManager.getConnection(url, user, password);
                var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
        } catch (Exception ignored) {
        }
    }

    private static boolean canConnect(String url, String user, String password) {
        try (var ignored = DriverManager.getConnection(url, user, password)) { return true; }
        catch (Exception exception) { return false; }
    }

    private static String setting(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
