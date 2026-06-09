package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class PostgreSqlMigrationTest {

    @Test
    void aplicaEsquemaCompletoEnPostgreSqlRealCuandoSeConfiguraPorVariables() throws Exception {
        String url = System.getenv("TPV_ERP_TEST_DB_URL");
        String user = System.getenv("TPV_ERP_TEST_DB_USER");
        String password = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
        assumeTrue(url != null && !url.isBlank(), "TPV_ERP_TEST_DB_URL no configurada");

        String schema = "tpv_erp_test_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure()
                .dataSource(url, user, password)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .load()
                .migrate();

            try (Connection connection = DriverManager.getConnection(url, user, password);
                    Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery("""
                        select count(*)
                        from information_schema.tables
                        where table_schema = '%s'
                          and table_name in (
                            'instalacion', 'empresa', 'tienda', 'licencia', 'terminal',
                            'rol', 'permiso', 'rol_permiso', 'usuario', 'sesion',
                            'auditoria', 'configuracion_backup', 'ejecucion_backup',
                            'impuesto_tienda', 'almacen', 'familia', 'subfamilia',
                            'producto', 'producto_identificador', 'producto_precio',
                            'existencia', 'movimiento_stock', 'salida_almacen',
                            'salida_almacen_linea', 'cliente', 'movimiento_saldo_socio',
                            'proveedor', 'comercial', 'proveedor_comercial',
                            'producto_proveedor', 'metodo_pago', 'contador_documento',
                            'documento', 'documento_linea', 'documento_pago',
                            'documento_relacion')
                        """.formatted(schema))) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(36);
            }

            try (Connection connection = DriverManager.getConnection(url, user, password);
                    Statement statement = connection.createStatement();
                    ResultSet column = statement.executeQuery("""
                        select is_nullable
                        from information_schema.columns
                        where table_schema = '%s'
                          and table_name = 'producto_proveedor'
                          and column_name = 'referencia_proveedor'
                        """.formatted(schema))) {
                assertThat(column.next()).isTrue();
                assertThat(column.getString("is_nullable")).isEqualTo("YES");
            }

            try (Connection connection = DriverManager.getConnection(url, user, password);
                    Statement statement = connection.createStatement();
                    ResultSet index = statement.executeQuery("""
                        select count(*)
                        from pg_indexes
                        where schemaname = '%s'
                          and indexname = 'ux_producto_proveedor_referencia'
                        """.formatted(schema))) {
                assertThat(index.next()).isTrue();
                assertThat(index.getInt(1)).isZero();
            }
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                    Statement statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }
}
