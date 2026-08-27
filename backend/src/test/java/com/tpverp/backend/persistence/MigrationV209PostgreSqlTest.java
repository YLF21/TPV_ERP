package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

class MigrationV209PostgreSqlTest {

    @Test
    void retiresUnprovenCustomFiscalTemplatesAndReturnsTheirRoutesToBuiltIn()
            throws Exception {
        var url = setting("TPV_ERP_TEST_DB_URL",
                "jdbc:postgresql://localhost:5432/tpv_erp_test");
        var user = setting("TPV_ERP_TEST_DB_USER", "postgres");
        var password = setting("TPV_ERP_TEST_DB_PASSWORD", "admin");
        assumeTrue(canConnect(url, user, password), "PostgreSQL de pruebas no disponible");
        var schema = "fiscal_template_v209_"
                + UUID.randomUUID().toString().replace("-", "");
        var companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var storeTemplateId = UUID.randomUUID();
        var companyTemplateId = UUID.randomUUID();
        var systemTemplateId = UUID.randomUUID();
        try {
            FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                    .dataSource(url, user, password)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(true)
                    .target(MigrationVersion.fromVersion("208"))
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                statement.execute("set search_path to " + schema);
                statement.executeUpdate("""
                        insert into empresa (id, tax_id, razon_social, domicilio_fiscal)
                        values ('%s', 'B12345674', 'Empresa plantilla', '{
                          "linea1":"Calle Uno", "codigoPostal":"35001",
                          "ciudad":"Las Palmas", "provincia":"Las Palmas", "pais":"ES"
                        }')
                        """.formatted(companyId));
                statement.executeUpdate("""
                        insert into tienda (
                          id, empresa_id, nombre, direccion, address_normalized_hash,
                          timezone, moneda, locale, codigo_tienda)
                        values ('%s', '%s', 'Tienda plantilla', '{
                          "linea1":"Calle Uno", "codigoPostal":"35001",
                          "ciudad":"Las Palmas", "provincia":"Las Palmas", "pais":"ES"
                        }', 'V209-STORE', 'Atlantic/Canary', 'EUR', 'es-ES', '001')
                        """.formatted(storeId, companyId));
                insertTemplate(statement, storeTemplateId, companyId, storeId,
                        "TICKET", "TICKET_80", "STORE", "CUSTOM_TICKET");
                insertTemplate(statement, companyTemplateId, companyId, null,
                        "FACTURA_VENTA", "A4", "COMPANY", "CUSTOM_INVOICE");
                insertTemplate(statement, systemTemplateId, null, null,
                        "RECTIFICATIVA_VENTA", "A4", "SYSTEM", "SYSTEM_RECTIFICATION");
                statement.executeUpdate("""
                        insert into configuracion_origen_plantilla_documento (
                          tienda_id, tipo, formato, origen, version)
                        values
                          ('%1$s', 'TICKET', 'TICKET_80', 'IMPORTED', 4),
                          ('%1$s', 'FACTURA_VENTA', 'A4', 'IMPORTED', 7)
                        """.formatted(storeId));
            }

            FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                    .dataSource(url, user, password)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                statement.execute("set search_path to " + schema);
                try (var templates = statement.executeQuery("""
                        select id, estado, motivo_retirada, artifact_reference
                        from plantilla_documento
                        order by codigo
                        """)) {
                    var seen = 0;
                    while (templates.next()) {
                        seen++;
                        var id = templates.getObject("id", UUID.class);
                        if (id.equals(systemTemplateId)) {
                            assertThat(templates.getString("estado")).isEqualTo("ACTIVE");
                            assertThat(templates.getString("motivo_retirada")).isNull();
                        } else {
                            assertThat(templates.getString("estado")).isEqualTo("RETIRED");
                            assertThat(templates.getString("motivo_retirada"))
                                    .isEqualTo("FISCAL_VISUAL_VALIDATION_REQUIRED");
                        }
                        assertThat(templates.getString("artifact_reference"))
                                .startsWith("signed:v209/");
                    }
                    assertThat(seen).isEqualTo(3);
                }
                try (var routes = statement.executeQuery("""
                        select tipo, origen, version
                        from configuracion_origen_plantilla_documento
                        order by tipo
                        """)) {
                    assertThat(routes.next()).isTrue();
                    assertThat(routes.getString("tipo")).isEqualTo("FACTURA_VENTA");
                    assertThat(routes.getString("origen")).isEqualTo("INTEGRATED");
                    assertThat(routes.getLong("version")).isEqualTo(8);
                    assertThat(routes.next()).isTrue();
                    assertThat(routes.getString("tipo")).isEqualTo("TICKET");
                    assertThat(routes.getString("origen")).isEqualTo("INTEGRATED");
                    assertThat(routes.getLong("version")).isEqualTo(5);
                    assertThat(routes.next()).isFalse();
                }
            }
        } finally {
            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }

    private static void insertTemplate(
            java.sql.Statement statement,
            UUID id,
            UUID companyId,
            UUID storeId,
            String type,
            String format,
            String scope,
            String code) throws Exception {
        statement.executeUpdate("""
                insert into plantilla_documento (
                  id, empresa_id, tienda_id, tipo, formato, ambito, codigo,
                  version_plantilla, nombre, estado, schema_version,
                  artifact_reference, sha256, creada_en, validada_en,
                  activada_en, version)
                values (
                  '%s', %s, %s, '%s', '%s', '%s', '%s', 1, '%s',
                  'ACTIVE', 1, 'signed:v209/%s', '%s', current_timestamp,
                  current_timestamp, current_timestamp, 0)
                """.formatted(
                id,
                sqlUuid(companyId),
                sqlUuid(storeId),
                type,
                format,
                scope,
                code,
                code,
                code.toLowerCase(java.util.Locale.ROOT),
                "a".repeat(64)));
    }

    private static String sqlUuid(UUID value) {
        return value == null ? "null" : "'" + value + "'";
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
