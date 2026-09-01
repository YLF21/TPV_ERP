package com.tpverp.backend.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class CommercialBootstrapServicePostgreSqlTest {

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void bootstrapsTwiceAfterV238WithoutReclaimingGeneralCodes() throws Exception {
        String url = setting("TPV_ERP_TEST_DB_URL",
                "jdbc:postgresql://localhost:5432/tpv_erp_test");
        String user = setting("TPV_ERP_TEST_DB_USER", "tpv_erp_test");
        String password = setting("TPV_ERP_TEST_DB_PASSWORD", "admin");
        assumeTrue(canConnect(url, user, password), "PostgreSQL de pruebas no disponible");
        String schema = "commercial_bootstrap_v238_"
                + UUID.randomUUID().toString().replace("-", "");

        try {
            migrate(url, user, password, schema);
            var dataSource = new DriverManagerDataSource(
                    schemaUrl(url, schema), user, password);
            var jdbc = new JdbcTemplate(dataSource);
            var transactions = new TransactionTemplate(
                    new DataSourceTransactionManager(dataSource));
            var service = new CommercialBootstrapService(jdbc);
            UUID companyId = UUID.randomUUID();
            UUID newStoreId = UUID.randomUUID();
            UUID existingStoreId = UUID.randomUUID();
            UUID existingGeneralId = UUID.randomUUID();

            insertCompany(jdbc, companyId);
            insertStore(jdbc, newStoreId, companyId, "901");
            insertStore(jdbc, existingStoreId, companyId, "902");
            jdbc.update("""
                    insert into familia
                      (id, tienda_id, family_id, family_code, nombre, predeterminada)
                    values (?, ?, 'GENERAL', '000', 'GENERAL', true)
                    """, existingGeneralId, existingStoreId);

            transactions.executeWithoutResult(ignored -> service.initialize());
            transactions.executeWithoutResult(ignored -> service.initialize());

            assertGeneral(jdbc, newStoreId, newStoreId);
            assertGeneral(jdbc, existingStoreId, existingGeneralId);
            assertThat(jdbc.queryForObject("""
                    select count(*)
                    from familia f
                    left join familia_codigo_reservado r
                      on r.tienda_id = f.tienda_id and r.family_code = f.family_code
                    where r.tienda_id is null
                    """, Integer.class)).isZero();
            assertThat(jdbc.queryForObject("""
                    select count(*)
                    from producto_identificador
                    where tienda_id in (?, ?) and tipo = 'CODIGO' and valor = '0'
                    """, Integer.class, newStoreId, existingStoreId)).isEqualTo(2);

            UUID collisionStoreId = UUID.randomUUID();
            insertStore(jdbc, collisionStoreId, companyId, "903");
            jdbc.update("""
                    insert into familia
                      (id, tienda_id, family_id, family_code, nombre, predeterminada)
                    values (?, ?, 'ID-COLLISION', '001', 'COLISION DE ID', false)
                    """, collisionStoreId, existingStoreId);

            assertThatThrownBy(() -> transactions.executeWithoutResult(
                    ignored -> service.initializeStore(collisionStoreId, companyId)))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject("""
                    select count(*)
                    from familia
                    where tienda_id = ? and (predeterminada or family_code = '000')
                    """, Integer.class, collisionStoreId)).isZero();
            assertThat(jdbc.queryForObject("""
                    select count(*)
                    from familia_codigo_reservado
                    where tienda_id = ? and family_code = '000'
                    """, Integer.class, collisionStoreId)).isZero();
        } finally {
            dropSchema(url, user, password, schema);
        }
    }

    private static void assertGeneral(JdbcTemplate jdbc, UUID storeId, UUID expectedId) {
        assertThat(jdbc.queryForObject("""
                select count(*)
                from familia
                where tienda_id = ? and (predeterminada or family_code = '000')
                """, Integer.class, storeId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select id
                from familia
                where tienda_id = ? and predeterminada and family_code = '000'
                """, UUID.class, storeId)).isEqualTo(expectedId);
        assertThat(jdbc.queryForObject("""
                select count(*)
                from familia_codigo_reservado
                where tienda_id = ? and family_code = '000'
                """, Integer.class, storeId)).isEqualTo(1);
    }

    private static void insertCompany(JdbcTemplate jdbc, UUID companyId) {
        jdbc.update("""
                insert into empresa (id, tax_id, razon_social, domicilio_fiscal)
                values (?, 'B23890001', 'Empresa bootstrap V238', cast(? as jsonb))
                """, companyId, address());
    }

    private static void insertStore(
            JdbcTemplate jdbc, UUID storeId, UUID companyId, String storeCode) {
        jdbc.update("""
                insert into tienda
                  (id, empresa_id, codigo_tienda, nombre, direccion,
                   address_normalized_hash, timezone, moneda, locale)
                values (?, ?, ?, 'Tienda bootstrap V238', cast(? as jsonb), ?,
                  'Atlantic/Canary', 'EUR', 'es-ES')
                """, storeId, companyId, storeCode, address(), "bootstrap-" + storeCode);
    }

    private static void migrate(String url, String user, String password, String schema) {
        FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                .dataSource(url, user, password)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .load()
                .migrate();
    }

    private static void dropSchema(String url, String user, String password, String schema) {
        try (var connection = DriverManager.getConnection(url, user, password);
                var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
        } catch (Exception ignored) {
            // Preserve the original assertion failure.
        }
    }

    private static boolean canConnect(String url, String user, String password) {
        try (var ignored = DriverManager.getConnection(url, user, password)) {
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private static String schemaUrl(String url, String schema) {
        return url + (url.contains("?") ? "&" : "?")
                + "currentSchema=" + schema + ",public";
    }

    private static String setting(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String address() {
        return """
                {"linea1":"Test","ciudad":"Las Palmas","codigoPostal":"35001",
                 "provincia":"Las Palmas","pais":"ES"}
                """;
    }
}
