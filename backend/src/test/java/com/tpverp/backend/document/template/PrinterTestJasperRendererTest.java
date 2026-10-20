package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

class PrinterTestJasperRendererTest {

    private static JasperReport report;

    @BeforeAll
    static void compilePackagedSource() throws Exception {
        try (var input = new ClassPathResource(
                "reports/operational-receipts/PRUEBA_IMPRESORA_TICKET_80.jrxml")
                .getInputStream()) {
            report = JasperCompileManager.getInstance(SafeJrxmlCompiler.secureContext())
                    .compile(input);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void printsOnlyStoreHeaderTestTitleAndSolidFullWidthBar(boolean includeLogo) throws Exception {
        var header = header();
        if (includeLogo) header.put("logo_contenido", logo());
        var print = JasperFillManager.getInstance(SafeJrxmlCompiler.secureContext())
                .fill(report, new HashMap<>(), new JRMapCollectionDataSource(List.of(header)));
        byte[] pdfBytes = JasperExportManager.getInstance(SafeJrxmlCompiler.secureContext())
                .exportToPdf(print);
        byte[] pngBytes = InvoiceJasperRenderer.ticketRaster(print);

        try (var pdf = Loader.loadPDF(pdfBytes)) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
            assertThat(pdf.getPage(0).getMediaBox().getWidth()).isEqualTo(227f);
            String text = new PDFTextStripper().getText(pdf).replaceAll("\\s+", " ");
            assertThat(text).contains("TIENDA PEÑA", "Empresa Álvarez", "NIF: B12345678",
                    "Tel.: 900000001", "Calle Muñoz 1", "35001 Las Palmas",
                    "tienda@example.test", "IMPRESIÓN DE PRUEBA")
                    .doesNotContain("null", "Factura", "Total", "TEST-", "Terminal", "QR");
        }
        var raster = ImageIO.read(new ByteArrayInputStream(pngBytes));
        assertThat(raster.getWidth()).isEqualTo(576);
        assertThat(raster.getHeight()).isBetween(150, 700);
        assertFullWidthBar(raster);
        var output = Path.of("target", "printer-test-verification");
        Files.createDirectories(output);
        String name = includeLogo ? "prueba-con-logo" : "prueba-sin-logo";
        Files.write(output.resolve(name + ".pdf"), pdfBytes);
        Files.write(output.resolve(name + ".png"), pngBytes);
    }

    @Test
    void wrapsLongStoreHeaderWithoutLosingTextOrTheBlackBar() throws Exception {
        var header = header();
        String name = "TIENDA PEÑA CON UN NOMBRE COMERCIAL LARGO PARA COMPROBAR LA CABECERA";
        String address = "Avenida de la Constitución con una dirección larga hasta el FINAL";
        header.put("nombre_cabecera", name);
        header.put("direccion", address);
        header.put("nombre_empresa_secundario", null);
        header.put("telefono", null);
        header.put("email", null);
        var print = JasperFillManager.getInstance(SafeJrxmlCompiler.secureContext())
                .fill(report, new HashMap<>(), new JRMapCollectionDataSource(List.of(header)));
        try (var pdf = Loader.loadPDF(JasperExportManager.exportReportToPdf(print))) {
            assertThat(new PDFTextStripper().getText(pdf).replaceAll("\\s+", " "))
                    .contains(name, address, "IMPRESIÓN DE PRUEBA")
                    .doesNotContain("null", "Tel.:");
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
        }
        assertFullWidthBar(ImageIO.read(new ByteArrayInputStream(
                InvoiceJasperRenderer.ticketRaster(print))));
    }

    private static Map<String, Object> header() {
        var header = new HashMap<String, Object>();
        header.put("nombre_cabecera", "TIENDA PEÑA");
        header.put("nombre_empresa_secundario", "Empresa Álvarez");
        header.put("cif", "B12345678");
        header.put("telefono", "900000001");
        header.put("email", "tienda@example.test");
        header.put("direccion", "Calle Muñoz 1");
        header.put("codigo_postal", "35001");
        header.put("ciudad", "Las Palmas");
        header.put("pais", "ES");
        return header;
    }

    private static byte[] logo() throws Exception {
        var image = new BufferedImage(160, 40, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 160, 40);
            graphics.setColor(Color.BLACK);
            graphics.drawString("LOGO TIENDA", 20, 25);
        } finally {
            graphics.dispose();
        }
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private static void assertFullWidthBar(BufferedImage image) {
        int solidRows = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            boolean solid = true;
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xffffff) != 0) {
                    solid = false;
                    break;
                }
            }
            if (solid) solidRows++;
        }
        assertThat(solidRows).as("Black bar covering every printable raster column")
                .isBetween(25, 35);
    }
}
