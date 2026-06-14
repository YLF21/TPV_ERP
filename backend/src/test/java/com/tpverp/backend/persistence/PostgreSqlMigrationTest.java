package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
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

            try (Connection connection = DriverManager.getConnection(url, user, password);
                    Statement statement = connection.createStatement();
                    ResultSet constraints = statement.executeQuery("""
                        select count(*)
                        from information_schema.table_constraints
                        where constraint_schema = '%s'
                          and table_name = 'producto_proveedor'
                          and constraint_name in (
                            'producto_proveedor_referencia_proveedor_check',
                            'producto_proveedor_referencia_proveedor_check1')
                        """.formatted(schema))) {
                assertThat(constraints.next()).isTrue();
                assertThat(constraints.getInt(1)).isZero();
            }

            verifyProductSupplierConstraints(url, user, password, schema);
            verifyFiscalIdentityColumns(url, user, password, schema);
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                    Statement statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }

    private static void verifyProductSupplierConstraints(
            String url, String user, String password, String schema) throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID taxId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        UUID firstProductId = UUID.randomUUID();
        UUID secondProductId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into %1$s.empresa (id, tax_id, razon_social, domicilio_fiscal)
                    values ('%2$s', 'B00000001', 'Empresa', '{
                        "linea1":"Calle Uno",
                        "ciudad":"Las Palmas",
                        "codigoPostal":"35001",
                        "provincia":"Las Palmas",
                        "pais":"ES"
                    }')
                    """.formatted(schema, companyId));
            statement.executeUpdate("""
                    insert into %1$s.tienda (
                        id, empresa_id, nombre, direccion, address_normalized_hash,
                        timezone, moneda, locale, codigo_tienda)
                    values (
                        '%2$s', '%3$s', 'Tienda', '{
                            "linea1":"Calle Uno",
                            "ciudad":"Las Palmas",
                            "codigoPostal":"35001",
                            "provincia":"Las Palmas",
                            "pais":"ES"
                        }', 'hash', 'Atlantic/Canary', 'EUR', 'es-ES', '001')
                    """.formatted(schema, storeId, companyId));
            statement.executeUpdate("""
                    insert into %1$s.impuesto_tienda (id, tienda_id, porcentaje)
                    values ('%2$s', '%3$s', 21)
                    """.formatted(schema, taxId, storeId));
            statement.executeUpdate("""
                    insert into %1$s.familia (id, tienda_id, nombre)
                    values ('%2$s', '%3$s', 'GENERAL')
                    """.formatted(schema, familyId, storeId));
            statement.executeUpdate("""
                    insert into %1$s.producto (
                        id, tienda_id, familia_id, impuesto_id, nombre)
                    values
                        ('%2$s', '%4$s', '%5$s', '%6$s', 'Producto 1'),
                        ('%3$s', '%4$s', '%5$s', '%6$s', 'Producto 2')
                    """.formatted(
                    schema, firstProductId, secondProductId, storeId, familyId, taxId));
            statement.executeUpdate("""
                    insert into %1$s.proveedor (
                        id, empresa_id, razon_social, tipo_documento, numero_documento)
                    values ('%2$s', '%3$s', 'Proveedor', 'CIF', 'B00000002')
                    """.formatted(schema, supplierId, companyId));

            statement.executeUpdate("""
                    insert into %1$s.producto_proveedor (
                        id, producto_id, proveedor_id, referencia_proveedor)
                    values ('%2$s', '%3$s', '%4$s', null)
                    """.formatted(schema, UUID.randomUUID(), firstProductId, supplierId));

            try (ResultSet nullReference = statement.executeQuery("""
                    select referencia_proveedor is null
                    from %1$s.producto_proveedor
                    where producto_id = '%2$s'
                    """.formatted(schema, firstProductId))) {
                assertThat(nullReference.next()).isTrue();
                assertThat(nullReference.getBoolean(1)).isTrue();
            }

            statement.executeUpdate("""
                    update %1$s.producto_proveedor
                    set referencia_proveedor = 'REF-1'
                    where producto_id = '%2$s'
                    """.formatted(schema, firstProductId));
            statement.executeUpdate("""
                    insert into %1$s.producto_proveedor (
                        id, producto_id, proveedor_id, referencia_proveedor)
                    values ('%2$s', '%3$s', '%4$s', 'REF-1')
                    """.formatted(schema, UUID.randomUUID(), secondProductId, supplierId));

            try (ResultSet repeatedReference = statement.executeQuery("""
                    select count(*)
                    from %1$s.producto_proveedor
                    where proveedor_id = '%2$s'
                      and referencia_proveedor = 'REF-1'
                    """.formatted(schema, supplierId))) {
                assertThat(repeatedReference.next()).isTrue();
                assertThat(repeatedReference.getInt(1)).isEqualTo(2);
            }

            assertThatThrownBy(() -> statement.executeUpdate("""
                    insert into %1$s.producto_proveedor (
                        id, producto_id, proveedor_id, referencia_proveedor)
                    values ('%2$s', '%3$s', '%4$s', null)
                    """.formatted(schema, UUID.randomUUID(), firstProductId, supplierId)))
                    .isInstanceOfSatisfying(SQLException.class,
                            exception -> assertThat(exception.getSQLState()).isEqualTo("23505"));
        }
    }

    private static void verifyFiscalIdentityColumns(
            String url, String user, String password, String schema) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement();
                ResultSet columns = statement.executeQuery("""
                    select count(*)
                    from information_schema.columns
                    where table_schema = '%s'
                      and (
                        (table_name = 'tienda' and column_name = 'codigo_tienda'
                            and is_nullable = 'NO')
                        or
                        (table_name = 'licencia'
                            and column_name in ('tax_id', 'taxpayer_type'))
                      )
                    """.formatted(schema))) {
            assertThat(columns.next()).isTrue();
            assertThat(columns.getInt(1)).isEqualTo(3);
        }
    }
}
