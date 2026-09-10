package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FlywayPostgreSqlConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class OperationalReceiptJasperRendererPostgreSqlTest {

    private static final String URL = System.getenv("TPV_ERP_TEST_DB_URL");
    private static final String USER = System.getenv("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA =
            "operational_receipt_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant COLLECTED_AT = Instant.parse("2026-09-09T10:15:30Z");

    static {
        execute("create schema " + SCHEMA);
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL
                + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @AfterAll
    static void dropSchema() {
        execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"FACTURA_VENTA", "ALBARAN_VENTA", "TICKET"})
    void rendersPersistedPaymentByBothIdsAndKeepsItsHistoricalBalance(String documentType)
            throws Exception {
        var fixture = insertFixture(documentType);
        var renderer = new OperationalReceiptJasperRenderer(dataSource);

        for (UUID paymentId : new UUID[] {fixture.requestId(), fixture.paymentId()}) {
            var rendered = renderer.renderPendingCollection(fixture.documentId(), paymentId);

            assertThat(rendered.pdf()).startsWith(0x25, 0x50, 0x44, 0x46);
            try (var pdf = Loader.loadPDF(rendered.pdf())) {
                assertThat(pdf.getNumberOfPages()).isEqualTo(1);
                assertThat(new PDFTextStripper().getText(pdf).replace(',', '.'))
                        .contains("JUSTIFICANTE DE COBRO", "DOCUMENTO NO FISCAL",
                                "Cobro de " + fixture.number(),
                                "Tienda TEST", "Cliente TEST", "TRANSFERENCIA",
                                "TR-RECIBO-TEST", "TOTAL COBRADO", "20.00", "SALDO PENDIENTE", "80.00")
                        .doesNotContain("50.00", fixture.requestId().toString(),
                                fixture.paymentId().toString(), fixture.documentId().toString());
                assertBoldText(pdf, "JUSTIFICANTE DE COBRO");
                assertBoldText(pdf, "TOTAL COBRADO");
                var page = new PDFRenderer(pdf).renderImageWithDPI(0, 300);
                var bitmap = new BinaryBitmap(new HybridBinarizer(
                        new BufferedImageLuminanceSource(page)));
                var barcode = new MultiFormatReader().decode(bitmap, Map.of(
                        DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.CODE_128),
                        DecodeHintType.TRY_HARDER, true));
                assertThat(barcode.getText()).isEqualTo(fixture.number());
            }
            var raster = ImageIO.read(new ByteArrayInputStream(rendered.png()));
            assertThat(raster).isNotNull();
            assertThat(raster.getWidth()).isEqualTo(576);
            assertThat(raster.getHeight()).isGreaterThan(100);
            var rasterBarcode = new MultiFormatReader().decode(
                    new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(raster))),
                    Map.of(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.CODE_128),
                            DecodeHintType.TRY_HARDER, true));
            assertThat(rasterBarcode.getText()).isEqualTo(fixture.number());
            if ("FACTURA_VENTA".equals(documentType) && paymentId.equals(fixture.requestId())) {
                var output = Path.of("target", "receipt-jasper-verification");
                Files.createDirectories(output);
                Files.write(output.resolve("cobro-pendiente.pdf"), rendered.pdf());
                Files.write(output.resolve("cobro-pendiente.png"), rendered.png());
            }
        }

        assertThat(jdbc.queryForObject("select count(*) from documento_pago where documento_id = ?",
                Integer.class, fixture.documentId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select sum(importe) from documento_pago where documento_id = ?",
                java.math.BigDecimal.class, fixture.documentId())).isEqualByComparingTo("50.00");
    }

    private Fixture insertFixture(String documentType) {
        var companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var warehouseId = UUID.randomUUID();
        var customerId = UUID.randomUUID();
        var methodId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        var paymentId = UUID.randomUUID();
        var requestId = UUID.randomUUID();
        var number = switch (documentType) {
            case "FACTURA_VENTA" -> "FV-001-26-000001";
            case "ALBARAN_VENTA" -> "AV-001-26-000001";
            default -> "001-260909-00001";
        };
        var taxId = switch (documentType) {
            case "FACTURA_VENTA" -> "B00000001";
            case "ALBARAN_VENTA" -> "B00000002";
            default -> "B00000003";
        };
        var address = "{\"linea1\":\"Calle Prueba 1\",\"ciudad\":\"Las Palmas\","
                + "\"codigoPostal\":\"35001\",\"provincia\":\"Las Palmas\",\"pais\":\"ES\"}";
        jdbc.update("""
                insert into empresa (id, tax_id, razon_social, domicilio_fiscal)
                values (?, ?, 'Empresa TEST', cast(? as jsonb))
                """, companyId, taxId, address);
        jdbc.update("""
                insert into tienda (id, empresa_id, nombre, direccion, address_normalized_hash,
                                    timezone, moneda, locale, codigo_tienda)
                values (?, ?, 'Tienda TEST', cast(? as jsonb), ?, 'Atlantic/Canary', 'EUR', 'es-ES', '001')
                """, storeId, companyId, address, storeId.toString());
        jdbc.update("insert into rol (id, tienda_id, nombre) values (?, ?, 'SELLER')", roleId, storeId);
        jdbc.update("""
                insert into usuario (id, tienda_id, nombre, user_name, password_hash, rol_id)
                values (?, ?, 'TEST', 'receipt-test', 'hash', ?)
                """, userId, storeId, roleId);
        jdbc.update("insert into almacen (id, tienda_id, nombre, predeterminado) values (?, ?, 'GENERAL', true)",
                warehouseId, storeId);
        jdbc.update("""
                insert into cliente (id, empresa_id, client_id, client_code_store_id, nombre_fiscal,
                                     tipo_documento, numero_documento, tarifa, descuento)
                values (?, ?, 'C-001-000001', ?, 'Cliente TEST', 'CIF', 'B00000002', 'VENTA', 0)
                """, customerId, companyId, storeId);
        jdbc.update("insert into metodo_pago (id, empresa_id, nombre) values (?, ?, 'TRANSFERENCIA')",
                methodId, companyId);
        jdbc.update("""
                insert into documento (id, tienda_id, almacen_id, tipo, estado, numero, fecha,
                                       creado_en, confirmado_en, creado_por, confirmado_por,
                                       cliente_id, total, cuenta_cobrar)
                values (?, ?, ?, ?, 'CONFIRMADO', ?, '2026-09-09', ?, ?, ?, ?, ?, 100.00, true)
                """, documentId, storeId, warehouseId, documentType, number,
                Timestamp.from(COLLECTED_AT.minusSeconds(60)), Timestamp.from(COLLECTED_AT.minusSeconds(30)),
                userId, userId, customerId);
        jdbc.update("""
                insert into documento_pago (id, documento_id, metodo_pago_id, posicion,
                                            importe, principal, creado_en, request_id, referencia, fecha_transferencia)
                values (?, ?, ?, 1, 20.00, true, ?, ?, 'TR-RECIBO-TEST', '2026-09-09')
                """, paymentId, documentId, methodId, Timestamp.from(COLLECTED_AT), requestId);
        jdbc.update("""
                insert into documento_pago (id, documento_id, metodo_pago_id, posicion,
                                            importe, principal, creado_en)
                values (?, ?, ?, 2, 30.00, false, ?)
                """, UUID.randomUUID(), documentId, methodId, Timestamp.from(COLLECTED_AT.plusSeconds(60)));
        return new Fixture(documentId, paymentId, requestId, number);
    }

    private static void assertBoldText(org.apache.pdfbox.pdmodel.PDDocument document,
            String expected) throws Exception {
        var fonts = new java.util.ArrayList<String>();
        var stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text,
                    java.util.List<org.apache.pdfbox.text.TextPosition> positions) {
                if (text.contains(expected)) {
                    positions.forEach(position -> fonts.add(position.getFont().getName()));
                }
            }
        };
        stripper.getText(document);
        assertThat(fonts).as("PDF font for %s", expected).isNotEmpty()
                .allMatch(font -> font.contains("Bold"));
    }

    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo preparar el esquema de prueba", exception);
        }
    }

    private record Fixture(UUID documentId, UUID paymentId, UUID requestId, String number) {}
}
