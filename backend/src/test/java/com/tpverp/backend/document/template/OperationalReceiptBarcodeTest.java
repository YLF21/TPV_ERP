package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

class OperationalReceiptBarcodeTest {
    private static JasperReport template;

    @BeforeAll
    static void compileTemplate() throws Exception {
        try (var input = new ClassPathResource(
                "reports/operational-receipts/cobro_pendiente.jrxml").getInputStream()) {
            template = JasperCompileManager.getInstance(SafeJrxmlCompiler.secureContext())
                    .compile(input);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "001-260909-00001", "123-260910-54321", "999-261231-99999",
        "FV-001-26-000001", "FV-123-26-543210", "FV-999-26-999999",
        "AV-001-26-000001", "AV-123-26-543210", "AV-999-26-999999"
    })
    void documentNumberIsReadableInPdfAndPrinterRaster(String documentNumber) throws Exception {
        Map<String, Object> fields = new HashMap<>();
        fields.put("nombre_cabecera", "Tienda TEST");
        fields.put("nombre_empresa_secundario", "Empresa TEST");
        fields.put("numero_documento", documentNumber);
        fields.put("fecha_cobro", Timestamp.from(Instant.parse("2026-09-09T10:15:30Z")));
        fields.put("terminal", "TEST");
        fields.put("cliente", "Cliente TEST");
        fields.put("metodo_pago", "TRANSFERENCIA");
        fields.put("importe_cobrado", new BigDecimal("20.00"));
        fields.put("saldo_pendiente", new BigDecimal("80.00"));
        var print = JasperFillManager.getInstance(SafeJrxmlCompiler.secureContext())
                .fill(template, new HashMap<>(), new JRMapCollectionDataSource(List.of(fields)));
        var pdfBytes = JasperExportManager.getInstance(SafeJrxmlCompiler.secureContext())
                .exportToPdf(print);
        var rasterBytes = InvoiceJasperRenderer.ticketRaster(print);
        var output = Path.of("target", "receipt-barcode-verification");
        Files.createDirectories(output);
        Files.write(output.resolve(documentNumber + ".pdf"), pdfBytes);
        Files.write(output.resolve(documentNumber + ".png"), rasterBytes);
        try (var pdf = Loader.loadPDF(pdfBytes)) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
            var page = new PDFRenderer(pdf).renderImageWithDPI(0, 300);
            var raster = ImageIO.read(new ByteArrayInputStream(rasterBytes));
            assertThat(raster.getWidth()).isEqualTo(576);
            assertAll(
                    () -> assertBarcode(page, documentNumber, "PDF"),
                    () -> assertBarcode(raster, documentNumber, "Printer raster"));
        }
    }

    private static void assertBarcode(BufferedImage image, String expected, String format) {
        var bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        try {
            var actual = new MultiFormatReader().decode(bitmap, Map.of(
                    DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.CODE_128),
                    DecodeHintType.TRY_HARDER, true)).getText();
            assertThat(actual).as("%s barcode for %s", format, expected).isEqualTo(expected);
        } catch (com.google.zxing.NotFoundException exception) {
            throw new AssertionError(format + " barcode is unreadable for " + expected, exception);
        }
    }
}
