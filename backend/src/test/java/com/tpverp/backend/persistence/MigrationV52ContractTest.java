package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class MigrationV52ContractTest {

    private static final String MIGRATION =
            "db/migration/V52__imagenes_edicion_masiva.sql";

    @Test
    void persistsStagedImagesAndCascadesWithTheDraft() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("create table producto_edicion_masiva_imagen")
                .contains("edicion_id uuid not null references producto_edicion_masiva(id) on delete cascade")
                .contains("producto_id uuid references producto(id) on delete set null")
                .contains("contenido bytea not null")
                .contains("tamano = octet_length(contenido)")
                .contains("tamano between 1 and 5242880")
                .contains("sha256 ~ '^[0-9a-f]{64}$'")
                .contains("ix_producto_edicion_masiva_imagen_edicion_posicion");
    }

    @Test
    void migratesByteaAndDeletesStagingWhenTheDraftIsDeleted() throws Exception {
        String url = setting(
                "TPV_ERP_TEST_DB_URL", "jdbc:postgresql://localhost:5432/tpv_erp_test");
        String user = setting("TPV_ERP_TEST_DB_USER", "postgres");
        String password = setting("TPV_ERP_TEST_DB_PASSWORD", "admin");
        assumeTrue(canConnect(url, user, password), "PostgreSQL de pruebas no disponible");
        String schema = "tpv_erp_v52_" + UUID.randomUUID().toString().replace("-", "");

        try {
            Flyway.configure()
                    .dataSource(url, user, password)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(true)
                    .load()
                    .migrate();
            assertCascade(url, user, password, schema);
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                    Statement statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }

    private static void assertCascade(
            String url, String user, String password, String schema) throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        UUID taxId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID editId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema);
            statement.executeUpdate("""
                    insert into empresa (id, tax_id, razon_social, domicilio_fiscal)
                    values ('%s', 'B00000001', 'Company', '{
                      "linea1":"Calle Uno","ciudad":"Las Palmas",
                      "codigoPostal":"35001","provincia":"Las Palmas","pais":"ES"
                    }')
                    """.formatted(companyId));
            statement.executeUpdate("""
                    insert into tienda (
                        id, empresa_id, codigo_tienda, nombre, direccion,
                        address_normalized_hash, timezone, moneda, locale)
                    values ('%s', '%s', '001', 'Store', '{
                      "linea1":"Calle Uno","ciudad":"Las Palmas",
                      "codigoPostal":"35001","provincia":"Las Palmas","pais":"ES"
                    }', 'hash-v52', 'Atlantic/Canary', 'EUR', 'es-ES')
                    """.formatted(storeId, companyId));
            statement.executeUpdate("""
                    insert into rol (id, tienda_id, nombre, protegido)
                    values ('%s', '%s', 'GESTOR', false)
                    """.formatted(roleId, storeId));
            statement.executeUpdate("""
                    insert into usuario (
                        id, tienda_id, nombre, password_hash, rol_id, user_id, user_name)
                    values ('%s', '%s', 'GESTOR', 'hash', '%s', 'E-900001', 'Gestor')
                    """.formatted(userId, storeId, roleId));
            statement.executeUpdate("""
                    insert into familia (id, tienda_id, nombre)
                    values ('%s', '%s', 'GENERAL')
                    """.formatted(familyId, storeId));
            statement.executeUpdate("""
                    insert into impuesto_tienda (id, tienda_id, porcentaje)
                    values ('%s', '%s', 21)
                    """.formatted(taxId, storeId));
            statement.executeUpdate("""
                    insert into producto (id, tienda_id, familia_id, impuesto_id, nombre)
                    values ('%s', '%s', '%s', '%s', 'Producto')
                    """.formatted(productId, storeId, familyId, taxId));
            statement.executeUpdate("""
                    insert into producto_edicion_masiva (
                        id, tienda_id, codigo, serie_id, numero_version, nombre,
                        contenido, creado_por, creado_en, actualizado_por, actualizado_en)
                    values (
                        '%s', '%s', '20260711001', '%s', 1, 'Imagenes', '[]',
                        '%s', now(), '%s', now())
                    """.formatted(editId, storeId, editId, userId, userId));
            statement.executeUpdate("""
                    insert into producto_edicion_masiva_imagen (
                        id, edicion_id, producto_id, posicion, nombre_archivo,
                        tipo_contenido, tamano, sha256, contenido)
                    values (
                        '%s', '%s', '%s', 0, 'foto.png', 'image/png', 3,
                        '%s', decode('010203', 'hex'))
                    """.formatted(imageId, editId, productId, "a".repeat(64)));
            try (var result = statement.executeQuery(
                    "select count(*) from producto_edicion_masiva_imagen")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
            statement.executeUpdate(
                    "delete from producto_edicion_masiva where id = '" + editId + "'");
            try (var result = statement.executeQuery(
                    "select count(*) from producto_edicion_masiva_imagen")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            }
        }
    }

    private static String setting(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean canConnect(String url, String user, String password) {
        try (Connection ignored = DriverManager.getConnection(url, user, password)) {
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
