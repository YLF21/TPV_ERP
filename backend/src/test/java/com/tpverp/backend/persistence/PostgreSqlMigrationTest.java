package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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
                            'producto_precio_historial',
                            'producto_importacion_excel_linea',
                            'existencia', 'movimiento_stock', 'salida_almacen',
                            'salida_almacen_linea', 'cliente', 'member_balance_movement',
                            'proveedor', 'comercial', 'proveedor_comercial',
                            'party_code_counter',
                            'producto_proveedor', 'metodo_pago', 'contador_documento',
                            'documento', 'documento_linea', 'documento_pago',
                            'documento_relacion', 'venta_aparcada', 'vale',
                            'sync_outbox', 'sync_inbox',
                            'configuracion_verifactu',
                            'cadena_fiscal', 'registro_fiscal',
                            'registro_fiscal_relacion', 'estado_envio_fiscal',
                            'intento_envio_fiscal', 'certificado_verifactu')
                        """.formatted(schema))) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(50);
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
            verifyFiscalIndexes(url, user, password, schema);
            verifyManagedCertificateIndexes(url, user, password, schema);
            verifyDeferredFiscalTriggers(url, user, password, schema);
            verifyImmutableFiscalRecords(url, user, password, schema);
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                    Statement statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }

    private static void verifyManagedCertificateIndexes(
            String url, String user, String password, String schema) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement();
                ResultSet indexes = statement.executeQuery("""
                    select indexname
                    from pg_indexes
                    where schemaname = '%s'
                      and indexname in (
                        'uq_certificado_verifactu_activo',
                        'uq_certificado_verifactu_anterior')
                    order by indexname
                    """.formatted(schema))) {
            var names = new ArrayList<String>();
            while (indexes.next()) {
                names.add(indexes.getString("indexname"));
            }
            assertThat(names).containsExactly(
                    "uq_certificado_verifactu_activo",
                    "uq_certificado_verifactu_anterior");
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
                    values ('%2$s', 'B00000001', 'Company', '{
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
                        '%2$s', '%3$s', 'Store', '{
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
                        id, empresa_id, razon_social, tipo_documento, numero_documento,
                        supplier_id)
                    values ('%2$s', '%3$s', 'Proveedor', 'CIF', 'B00000002', 'S-000001')
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

    private static void verifyImmutableFiscalRecords(
            String url, String user, String password, String schema) throws Exception {
        UUID installationId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID chainId = UUID.randomUUID();
        UUID firstRecordId = UUID.randomUUID();
        UUID secondRecordId = UUID.randomUUID();
        UUID otherCompanyId = UUID.randomUUID();
        UUID otherStoreId = UUID.randomUUID();
        UUID otherChainId = UUID.randomUUID();
        UUID otherRecordId = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate("""
                    insert into %1$s.instalacion (
                        id, referencia, public_key, creada_en, demo_hasta)
                    values (
                        '%2$s', 'TEST-FISCAL', 'public-key',
                        '2026-01-01T00:00:00Z', '2026-01-31T00:00:00Z')
                    """.formatted(schema, installationId));
            statement.executeUpdate("""
                    insert into %1$s.empresa (id, tax_id, razon_social, domicilio_fiscal)
                    values ('%2$s', 'B00000003', 'Company Fiscal', '{
                        "linea1":"Calle Fiscal",
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
                        '%2$s', '%3$s', 'Store Fiscal', '{
                            "linea1":"Calle Fiscal",
                            "ciudad":"Las Palmas",
                            "codigoPostal":"35001",
                            "provincia":"Las Palmas",
                            "pais":"ES"
                        }', 'fiscal-hash', 'Atlantic/Canary', 'EUR', 'es-ES', '001')
                    """.formatted(schema, storeId, companyId));
            statement.executeUpdate("""
                    insert into %1$s.cadena_fiscal (
                        id, empresa_id, instalacion_id, actualizada_en)
                    values ('%2$s', '%3$s', '%4$s', now())
                    """.formatted(schema, chainId, companyId, installationId));
            insertFiscalRecord(
                    statement, schema, firstRecordId, chainId, companyId,
                    installationId, storeId, 1, "A".repeat(64), null);
            insertFiscalRecord(
                    statement, schema, secondRecordId, chainId, companyId,
                    installationId, storeId, 2, "B".repeat(64), "A".repeat(64));
            updateFiscalChain(
                    statement, schema, chainId, secondRecordId, 2, "B".repeat(64));
            statement.executeUpdate("""
                    insert into %1$s.estado_envio_fiscal (
                        registro_id, estado, actualizado_en)
                    values ('%2$s', 'ENVIADO', now())
                    """.formatted(schema, firstRecordId));
            statement.executeUpdate("""
                    insert into %1$s.registro_fiscal_relacion (
                        cadena_id, registro_id, relacionado_id, tipo)
                    values ('%2$s', '%3$s', '%4$s', 'SUBSANA')
                    """.formatted(schema, chainId, secondRecordId, firstRecordId));

            insertCompanyAndStore(
                    statement, schema, otherCompanyId, otherStoreId,
                    "B00000004", "002", "other-fiscal-hash");
            statement.executeUpdate("""
                    insert into %1$s.cadena_fiscal (
                        id, empresa_id, instalacion_id, actualizada_en)
                    values ('%2$s', '%3$s', '%4$s', now())
                    """.formatted(schema, otherChainId, otherCompanyId, installationId));
            insertFiscalRecord(
                    statement, schema, otherRecordId, otherChainId, otherCompanyId,
                    installationId, otherStoreId, 1, "C".repeat(64), null);
            updateFiscalChain(
                    statement, schema, otherChainId, otherRecordId, 1, "C".repeat(64));
            connection.commit();
        }

        assertCommitRejected(url, user, password, "P0001", connection -> {
            UUID invalidRecordId = UUID.randomUUID();
            try (Statement statement = connection.createStatement()) {
                insertFiscalRecord(
                        statement, schema, invalidRecordId, chainId, companyId,
                        installationId, storeId, 3, "D".repeat(64), "C".repeat(64));
                updateFiscalChain(
                        statement, schema, chainId, invalidRecordId, 3, "D".repeat(64));
            }
        });
        assertCommitRejected(url, user, password, "P0001", connection -> {
            try (Statement statement = connection.createStatement()) {
                updateFiscalChain(
                        statement, schema, chainId, secondRecordId, 1, "B".repeat(64));
            }
        });

        assertSqlState(url, user, password, "23505", """
                insert into %1$s.registro_fiscal (
                    id, cadena_id, empresa_id, instalacion_id, tienda_id,
                    secuencia, operacion, tipo_documento_fiscal, serie_numero,
                    fecha_expedicion, generado_en, zona_horaria, nif_emisor,
                    cuota_total, importe_total, huella_anterior, huella,
                    hash_snapshot, snapshot, version_formato, version_algoritmo,
                    version_aplicacion)
                select
                    '%2$s', cadena_id, empresa_id, instalacion_id, tienda_id,
                    2, operacion, tipo_documento_fiscal, 'DUPLICADO',
                    fecha_expedicion, generado_en, zona_horaria, nif_emisor,
                    cuota_total, importe_total, '%3$s', '%4$s',
                    hash_snapshot, snapshot, version_formato, version_algoritmo,
                    version_aplicacion
                from %1$s.registro_fiscal where id = '%5$s'
                """.formatted(
                schema, UUID.randomUUID(), "A".repeat(64), "D".repeat(64), firstRecordId));
        assertSqlState(url, user, password, "23514", """
                insert into %1$s.registro_fiscal (
                    id, cadena_id, empresa_id, instalacion_id, tienda_id,
                    secuencia, operacion, tipo_documento_fiscal, serie_numero,
                    fecha_expedicion, generado_en, zona_horaria, nif_emisor,
                    cuota_total, importe_total, huella_anterior, huella,
                    hash_snapshot, snapshot, version_formato, version_algoritmo,
                    version_aplicacion)
                select
                    '%2$s', cadena_id, empresa_id, instalacion_id, tienda_id,
                    3, operacion, tipo_documento_fiscal, 'HASH-INVALIDO',
                    fecha_expedicion, generado_en, zona_horaria, nif_emisor,
                    cuota_total, importe_total, '%3$s', 'hash-invalido',
                    hash_snapshot, snapshot, version_formato, version_algoritmo,
                    version_aplicacion
                from %1$s.registro_fiscal where id = '%4$s'
                """.formatted(schema, UUID.randomUUID(), "B".repeat(64), firstRecordId));
        assertSqlState(url, user, password, "23514", """
                insert into %1$s.estado_envio_fiscal (
                    registro_id, estado, actualizado_en)
                values ('%2$s', 'DESCONOCIDO', now())
                """.formatted(schema, secondRecordId));
        assertSqlState(url, user, password, "23514", """
                insert into %1$s.registro_fiscal_relacion (
                    cadena_id, registro_id, relacionado_id, tipo)
                values ('%2$s', '%3$s', '%3$s', 'ANULA')
                """.formatted(schema, chainId, firstRecordId));
        assertSqlState(url, user, password, "23503", """
                insert into %1$s.registro_fiscal_relacion (
                    cadena_id, registro_id, relacionado_id, tipo)
                values ('%2$s', '%3$s', '%4$s', 'RECTIFICA')
                """.formatted(schema, chainId, firstRecordId, otherRecordId));

        assertFiscalMutationRejected(url, user, password, """
                update %1$s.registro_fiscal
                set serie_numero = 'ALTERADO'
                where id = '%2$s'
                """.formatted(schema, firstRecordId));
        assertFiscalMutationRejected(url, user, password, """
                delete from %1$s.registro_fiscal where id = '%2$s'
                """.formatted(schema, firstRecordId));
        assertFiscalMutationRejected(url, user, password, """
                update %1$s.registro_fiscal_relacion
                set tipo = 'ANULA'
                where cadena_id = '%2$s'
                  and registro_id = '%3$s' and relacionado_id = '%4$s'
                """.formatted(schema, chainId, secondRecordId, firstRecordId));
        assertFiscalMutationRejected(url, user, password, """
                delete from %1$s.registro_fiscal_relacion
                where cadena_id = '%2$s'
                  and registro_id = '%3$s' and relacionado_id = '%4$s'
                """.formatted(schema, chainId, secondRecordId, firstRecordId));
    }

    private static void verifyFiscalIndexes(
            String url, String user, String password, String schema) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement();
                ResultSet indexes = statement.executeQuery("""
                    select indexname
                    from pg_indexes
                    where schemaname = '%s'
                      and indexname in (
                        'ix_registro_fiscal_documento',
                        'ix_registro_fiscal_empresa_fecha',
                        'ix_estado_envio_fiscal_estado',
                        'ix_intento_envio_fiscal_registro_fecha')
                    order by indexname
                    """.formatted(schema))) {
            var names = new ArrayList<String>();
            while (indexes.next()) {
                names.add(indexes.getString("indexname"));
            }
            assertThat(names).containsExactly(
                    "ix_estado_envio_fiscal_estado",
                    "ix_intento_envio_fiscal_registro_fecha",
                    "ix_registro_fiscal_documento",
                    "ix_registro_fiscal_empresa_fecha");
        }
    }

    private static void verifyDeferredFiscalTriggers(
            String url, String user, String password, String schema) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement();
                ResultSet triggers = statement.executeQuery("""
                    select count(*)
                    from pg_trigger trigger
                    join pg_class relation on relation.oid = trigger.tgrelid
                    join pg_namespace namespace on namespace.oid = relation.relnamespace
                    where namespace.nspname = '%s'
                      and trigger.tgname in (
                        'tr_registro_fiscal_cadena',
                        'tr_cadena_fiscal_cabeza')
                      and trigger.tgdeferrable
                      and trigger.tginitdeferred
                    """.formatted(schema))) {
            assertThat(triggers.next()).isTrue();
            assertThat(triggers.getInt(1)).isEqualTo(2);
        }
    }

    private static void insertCompanyAndStore(
            Statement statement, String schema, UUID companyId, UUID storeId,
            String taxId, String storeCode, String addressHash) throws SQLException {
        statement.executeUpdate("""
                insert into %1$s.empresa (id, tax_id, razon_social, domicilio_fiscal)
                values ('%2$s', '%4$s', 'Otra Company Fiscal', '{
                    "linea1":"Otra Calle Fiscal",
                    "ciudad":"Las Palmas",
                    "codigoPostal":"35001",
                    "provincia":"Las Palmas",
                    "pais":"ES"
                }')
                """.formatted(schema, companyId, storeId, taxId));
        statement.executeUpdate("""
                insert into %1$s.tienda (
                    id, empresa_id, nombre, direccion, address_normalized_hash,
                    timezone, moneda, locale, codigo_tienda)
                values (
                    '%2$s', '%3$s', 'Otra Store Fiscal', '{
                        "linea1":"Otra Calle Fiscal",
                        "ciudad":"Las Palmas",
                        "codigoPostal":"35001",
                        "provincia":"Las Palmas",
                        "pais":"ES"
                    }', '%5$s', 'Atlantic/Canary', 'EUR', 'es-ES', '%4$s')
                """.formatted(schema, storeId, companyId, storeCode, addressHash));
    }

    private static void insertFiscalRecord(
            Statement statement, String schema, UUID recordId, UUID chainId,
            UUID companyId, UUID installationId, UUID storeId, long sequence,
            String hash, String previousHash) throws SQLException {
        String previousHashSql = previousHash == null ? "null" : "'" + previousHash + "'";
        statement.executeUpdate("""
                insert into %1$s.registro_fiscal (
                    id, cadena_id, empresa_id, instalacion_id, tienda_id,
                    secuencia, operacion, tipo_documento_fiscal, serie_numero,
                    fecha_expedicion, generado_en, zona_horaria, nif_emisor,
                    cuota_total, importe_total, huella_anterior, huella,
                    hash_snapshot, snapshot, version_formato, version_algoritmo,
                    version_aplicacion)
                values (
                    '%2$s', '%3$s', '%4$s', '%5$s', '%6$s',
                    %7$d, 'ALTA', 'F2', '001-260101-%7$06d',
                    '2026-01-01', now(), 'Atlantic/Canary', 'B00000003',
                    1.00, 11.00, %9$s, '%8$s',
                    '%8$s', '{"total":11.00}', '1.0', 'SHA-256', 'test')
                """.formatted(
                schema, recordId, chainId, companyId, installationId, storeId,
                sequence, hash, previousHashSql));
    }

    private static void updateFiscalChain(
            Statement statement, String schema, UUID chainId, UUID recordId,
            long sequence, String hash) throws SQLException {
        statement.executeUpdate("""
                update %1$s.cadena_fiscal
                set ultimo_registro_id = '%2$s',
                    ultima_secuencia = %3$d,
                    ultima_huella = '%4$s',
                    actualizada_en = now()
                where id = '%5$s'
                """.formatted(schema, recordId, sequence, hash, chainId));
    }

    private static void assertFiscalMutationRejected(
            String url, String user, String password, String sql) {
        assertSqlState(url, user, password, "P0001", sql);
    }

    private static void assertSqlState(
            String url, String user, String password, String expectedSqlState, String sql) {
        assertThatThrownBy(() -> {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            }
        }).isInstanceOfSatisfying(SQLException.class,
                exception -> assertThat(exception.getSQLState()).isEqualTo(expectedSqlState));
    }

    private static void assertCommitRejected(
            String url, String user, String password, String expectedSqlState,
            SqlTransaction transaction) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setAutoCommit(false);
            transaction.execute(connection);
            assertThatThrownBy(connection::commit)
                    .isInstanceOfSatisfying(SQLException.class,
                            exception -> assertThat(exception.getSQLState())
                                    .isEqualTo(expectedSqlState));
        }
    }

    @FunctionalInterface
    private interface SqlTransaction {
        void execute(Connection connection) throws SQLException;
    }
}
