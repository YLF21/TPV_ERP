package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class PrinterTestJasperRendererPostgreSqlTest {

    private static final String URL = System.getenv("TPV_ERP_TEST_DB_URL");
    private static final String USER = System.getenv("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "printer_test_" + UUID.randomUUID().toString().replace("-", "");
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepareIsolatedStoreHeaderSchema() throws Exception {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute("create schema " + SCHEMA);
        }
        dataSource = new DriverManagerDataSource(
                URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA, USER, PASSWORD);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table empresa (id uuid primary key, razon_social text, tax_id text)");
        jdbc.execute("create table tienda (id uuid primary key, empresa_id uuid, nombre text, "
                + "telefono text, email text, direccion jsonb)");
        jdbc.execute("create table configuracion_documento_impreso_tienda "
                + "(tienda_id uuid primary key, mostrar_nombre_tienda boolean, logo_id uuid)");
        jdbc.execute("create table logo_documento_tienda (id uuid primary key, tienda_id uuid, contenido bytea)");
    }

    @AfterAll
    static void removeIsolatedSchema() throws Exception {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + SCHEMA + " cascade");
        }
    }

    @Test
    void readsOnlySelectedStoreAndRespectsItsCurrentHeaderWithoutAnySale() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID firstStore = UUID.randomUUID();
        UUID secondStore = UUID.randomUUID();
        jdbc.update("insert into empresa values (?, ?, ?)", companyId, "Empresa Álvarez", "B12345678");
        jdbc.update("insert into tienda values (?, ?, ?, ?, ?, ?::jsonb)", firstStore, companyId,
                "TIENDA PEÑA", "900111111", "primera@example.test",
                "{\"linea1\":\"Calle Muñoz 1\",\"codigoPostal\":\"35001\",\"ciudad\":\"Las Palmas\",\"pais\":\"ES\"}");
        jdbc.update("insert into tienda values (?, ?, ?, ?, ?, ?::jsonb)", secondStore, companyId,
                "OTRA TIENDA", "900222222", "segunda@example.test", "{}");
        jdbc.update("insert into configuracion_documento_impreso_tienda values (?, ?, null)", firstStore, true);
        var renderer = new OperationalReceiptJasperRenderer(dataSource);

        var receipt = renderer.renderPrinterTest(firstStore);
        assertThat(text(receipt.pdf())).contains("TIENDA PEÑA", "Empresa Álvarez", "900111111",
                "Calle Muñoz 1", "primera@example.test", "IMPRESIÓN DE PRUEBA")
                .doesNotContain("OTRA TIENDA", "900222222", "segunda@example.test");
        var output = Path.of("target", "printer-test-verification");
        Files.createDirectories(output);
        Files.write(output.resolve("prueba-tienda-postgresql.pdf"), receipt.pdf());
        Files.write(output.resolve("prueba-tienda-postgresql.png"), receipt.png());

        jdbc.update("update configuracion_documento_impreso_tienda set mostrar_nombre_tienda = false where tienda_id = ?",
                firstStore);
        assertThat(text(renderer.renderPrinterTest(firstStore).pdf()))
                .contains("Empresa Álvarez", "IMPRESIÓN DE PRUEBA").doesNotContain("TIENDA PEÑA");
        assertThat(text(renderer.renderPrinterTest(secondStore).pdf()))
                .contains("OTRA TIENDA", "900222222").doesNotContain("TIENDA PEÑA", "900111111");
        assertThatThrownBy(() -> renderer.renderPrinterTest(UUID.randomUUID()))
                .isInstanceOf(PrintRenderingException.class);
    }

    private static String text(byte[] bytes) throws Exception {
        try (var pdf = Loader.loadPDF(bytes)) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
            return new PDFTextStripper().getText(pdf).replaceAll("\\s+", " ");
        }
    }
}
