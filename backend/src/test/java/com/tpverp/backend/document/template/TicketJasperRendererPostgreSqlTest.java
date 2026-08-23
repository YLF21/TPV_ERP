package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentLine;
import com.tpverp.backend.organization.StoreDocumentPrintConfigurationService;
import com.tpverp.backend.organization.TicketPrintStyle;
import com.tpverp.backend.organization.TicketTemplateOrigin;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TicketJasperRendererPostgreSqlTest {

    private static final String URL = environment(
            "TPV_TEST_DB_URL", "jdbc:postgresql://localhost:5432/tpv_erp_test");
    private static final String USER = environment("TPV_TEST_DB_USERNAME", "tpv_erp_test");
    private static final String PASSWORD = environment("TPV_TEST_DB_PASSWORD", "admin");
    private static final String SCHEMA =
            "ticket_jasper_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-08-23T10:15:30Z");
    private static final String QR_URL = "https://prewww2.aeat.es/wlpl/TIKE-CONT/ValidarQR"
            + "?nif=B12345674&numserie=001-260823-000001"
            + "&fecha=23-08-2026&importe=10.00";

    static {
        execute("create schema " + SCHEMA);
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @TempDir private java.nio.file.Path temporaryDirectory;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL
                + (URL.contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @BeforeEach
    void clearDatabase() {
        jdbc.execute("truncate table instalacion, empresa cascade");
    }

    @AfterAll
    static void dropSchema() {
        execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(TicketPrintStyle.class)
    void rendersIntegratedTicketFromFrozenFiscalSnapshot(TicketPrintStyle style) throws Exception {
        var fixture = insertFixture();
        var storage = new DocumentTemplateArtifactStorage(temporaryDirectory);
        var bundle = new BuiltInTicketJasperBundle(new TicketJrxmlBundleCompiler(), storage);
        var printConfiguration = mock(StoreDocumentPrintConfigurationService.class);
        when(printConfiguration.ticketTemplateOrigin()).thenReturn(TicketTemplateOrigin.INTEGRATED);
        when(printConfiguration.ticketStyle()).thenReturn(style);
        var renderer = new TicketJasperRenderer(
                dataSource, mock(DocumentTemplateResolver.class), storage,
                printConfiguration, bundle);

        var rendered = renderer.renderForPrint(fixture.document());

        assertThat(rendered.pdf()).startsWith(0x25, 0x50, 0x44, 0x46);
        try (var pdf = Loader.loadPDF(rendered.pdf())) {
            assertThat(new PDFTextStripper().getText(pdf))
                    .contains(fixture.document().getNumero(), "QR tributario:");
        }
        var raster = ImageIO.read(new java.io.ByteArrayInputStream(rendered.png()));
        assertThat(raster.getWidth()).isEqualTo(576);
        assertThat(decodeQr(raster)).isEqualTo(QR_URL);
    }

    private Fixture insertFixture() throws Exception {
        try {
            return Objects.requireNonNull(new TransactionTemplate(transactionManager).execute(
                    status -> {
                        try {
                            return insertFixtureInTransaction();
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    }));
        } catch (IllegalStateException exception) {
            if (exception.getCause() instanceof Exception checked) {
                throw checked;
            }
            throw exception;
        }
    }

    private Fixture insertFixtureInTransaction() throws Exception {
        var companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var warehouseId = UUID.randomUUID();
        var installationId = UUID.randomUUID();
        var chainId = UUID.randomUUID();
        var taxId = UUID.randomUUID();
        var familyId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var lineId = UUID.randomUUID();
        var document = new CommercialDocument(
                storeId, warehouseId, CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 23), userId, BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, productId, 1, BigDecimal.ONE, "P-1", "Producto TEST", "VENTA",
                new BigDecimal("10.00"), BigDecimal.ZERO, true, "IVA", new BigDecimal("21.00")));
        document.confirm("001-260823-000001", userId, NOW, false);

        jdbc.update("""
                insert into empresa (id, tax_id, razon_social, domicilio_fiscal)
                values (?, 'B12345674', 'TPV ERP TEST', cast(? as jsonb))
                """, companyId, address());
        jdbc.update("""
                insert into tienda (
                    id, empresa_id, codigo_tienda, nombre, direccion,
                    address_normalized_hash, timezone, moneda, locale)
                values (?, ?, '001', 'Tienda TEST', cast(? as jsonb),
                    'ticket-jasper-store', 'Atlantic/Canary', 'EUR', 'es-ES')
                """, storeId, companyId, address());
        jdbc.update("insert into rol (id, tienda_id, nombre) values (?, ?, 'VENTA')",
                roleId, storeId);
        jdbc.update("""
                insert into usuario (id, tienda_id, nombre, user_name, password_hash, rol_id)
                values (?, ?, 'TEST', 'ticket-test', 'hash', ?)
                """, userId, storeId, roleId);
        jdbc.update("""
                insert into almacen (id, tienda_id, nombre, predeterminado)
                values (?, ?, 'GENERAL', true)
                """, warehouseId, storeId);
        jdbc.update("insert into impuesto_tienda (id, tienda_id, porcentaje, predeterminado) "
                + "values (?, ?, 21.00, true)", taxId, storeId);
        jdbc.update("insert into familia (id, tienda_id, nombre, predeterminada) "
                + "values (?, ?, 'GENERAL', true)", familyId, storeId);
        jdbc.update("insert into producto (id, tienda_id, familia_id, impuesto_id, nombre) "
                + "values (?, ?, ?, ?, 'Producto TEST')", productId, storeId, familyId, taxId);
        jdbc.update("""
                insert into instalacion (id, referencia, public_key, creada_en, demo_hasta)
                values (?, 'TICKET-JASPER-TEST', 'public-key', ?, ?)
                """, installationId, timestamp(NOW.minusSeconds(30)),
                timestamp(NOW.plus(30, java.time.temporal.ChronoUnit.DAYS).minusSeconds(30)));
        jdbc.update("""
                insert into cadena_fiscal (
                    id, empresa_id, instalacion_id, ultima_secuencia, actualizada_en)
                values (?, ?, ?, 0, ?)
                """, chainId, companyId, installationId, timestamp(NOW));
        jdbc.update("""
                insert into documento (
                    id, tienda_id, almacen_id, tipo, estado, numero, fecha,
                    creado_en, confirmado_en, creado_por, confirmado_por, total)
                values (?, ?, ?, 'TICKET', 'CONFIRMADO', ?, ?, ?, ?, ?, ?, 10.00)
                """, document.getId(), storeId, warehouseId, document.getNumero(),
                document.getFecha(), timestamp(NOW.minusSeconds(30)), timestamp(NOW),
                userId, userId);
        jdbc.update("""
                insert into documento_linea (
                    id, documento_id, producto_id, posicion, cantidad, codigo, nombre, tarifa,
                    precio_unitario, descuento, impuestos_incluidos, regimen_impuesto,
                    porcentaje_impuesto, base, impuesto, total, tipo_linea)
                values (?, ?, ?, 1, 1, 'P-1', 'Producto TEST', 'VENTA',
                    10.00, 0, true, 'IVA', 21.00, 8.26, 1.74, 10.00, 'PRODUCT')
                """, lineId, document.getId(), productId);
        var recordId = UUID.randomUUID();
        jdbc.update("""
                insert into registro_fiscal (
                    id, cadena_id, empresa_id, instalacion_id, tienda_id, documento_id,
                    secuencia, operacion, tipo_documento_fiscal, serie_numero,
                    fecha_expedicion, generado_en, zona_horaria, nif_emisor,
                    cuota_total, importe_total, huella, hash_snapshot, snapshot,
                    version_formato, version_algoritmo, version_aplicacion, modo_fiscal)
                values (?, ?, ?, ?, ?, ?, 1, 'ALTA', 'F2', ?, ?, ?,
                    'Atlantic/Canary', 'B12345674', 1.74, 10.00, ?, ?,
                    cast('{}' as jsonb), '1.0', 'SHA-256', 'TEST', 'VERIFACTU')
                """, recordId, chainId, companyId, installationId, storeId,
                document.getId(), document.getNumero(), document.getFecha(), timestamp(NOW),
                "A".repeat(64), "B".repeat(64));
        jdbc.update("""
                insert into snapshot_impresion_fiscal (
                    registro_id, modo_fiscal, entorno, version_formato, generador_version,
                    qr_url, qr_hash, qr_prefijo, qr_leyenda, aviso_pruebas, creado_en)
                values (?, 'VERIFACTU', 'TEST', 'AEAT_QR_0.5.0', 'ticket-test', ?, ?,
                    'QR tributario:', 'Factura verificable en la sede electrónica de la AEAT',
                    'ENTORNO DE PRUEBAS - SIN VALIDEZ FISCAL', ?)
                """, recordId, QR_URL, sha256(QR_URL), timestamp(NOW));
        jdbc.update("""
                update cadena_fiscal
                   set ultimo_registro_id = ?, ultima_huella = ?, ultima_secuencia = 1,
                       actualizada_en = ?
                 where id = ?
                """, recordId, "A".repeat(64), timestamp(NOW), chainId);
        return new Fixture(document);
    }

    private static String decodeQr(BufferedImage image) throws Exception {
        return new MultiFormatReader().decode(
                new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image))),
                Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE)).getText();
    }

    private static String address() {
        return "{\"linea1\":\"Calle Test 1\",\"ciudad\":\"Las Palmas\","
                + "\"codigoPostal\":\"35001\",\"provincia\":\"Las Palmas\",\"pais\":\"ES\"}";
    }

    private static java.sql.Timestamp timestamp(Instant value) {
        return java.sql.Timestamp.from(value);
    }

    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().withUpperCase().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String environment(String key, String fallback) {
        var value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo preparar el esquema de prueba", exception);
        }
    }

    private record Fixture(CommercialDocument document) {}
}
