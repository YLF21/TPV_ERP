package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.json.query.JsonQueryExecuterFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

class OperationalTicketFooterTemplateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void giftReceiptKeepsPricesHiddenAndPrintsIssuerFooter() throws Exception {
        var model = base();
        model.put("observations", "Conserve el ticket");
        var document = model.putObject("document");
        document.put("displayNumber", "RG-001-261011-00001");
        document.put("issueDateDisplay", "11/10/2026 10:00");
        document.put("barcode", "RG-001-261011-00001");
        var line = model.putArray("lines").addObject();
        line.put("code", "P-001");
        line.put("articleName", "Producto de regalo");
        line.put("quantityDisplay", "1");
        line.put("serials", "");

        try (var pdf = render("TICKET_REGALO_TICKET_80.jrxml", "regalo", model)) {
            var text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("TICKET REGALO", "Producto de regalo",
                    "ATENDIDO POR: EMPLEADA ORIGINAL", "TERMINAL: TPV01");
            assertThat(text).doesNotContain("PRECIO", "IMPORTE", "20,00 €");
            assertFooterRows(pdf);
        }
    }

    @Test
    void cashEntryPrintsOriginalOperatorTerminalAndAuthorizer() throws Exception {
        assertCashFooter("ENTRADA_CAJA_TICKET_80.jrxml", "entrada");
    }

    @Test
    void cashWithdrawalPrintsOriginalOperatorTerminalAndAuthorizer() throws Exception {
        assertCashFooter("RETIRADA_CAJA_TICKET_80.jrxml", "retirada");
    }

    private void assertCashFooter(String template, String outputName) throws Exception {
        var model = base();
        var movement = model.putObject("movement");
        movement.put("amountFormatted", "20,00 €");
        movement.put("createdAtFormatted", "11/10/2026 10:00");
        var lines = model.putArray("lines");
        lines.addObject().put("label", "Importe").put("value", "20.00");
        lines.addObject().put("label", "Autorizador").put("value", "ADMIN ORIGINAL");

        try (var pdf = render(template, outputName, model)) {
            var text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("ATENDIDO POR: EMPLEADA ORIGINAL",
                    "TERMINAL: TPV01", "Autorizador: ADMIN ORIGINAL");
            assertFooterRows(pdf);
        }
    }

    private ObjectNode base() {
        var root = mapper.createObjectNode();
        root.put("userName", "EMPLEADA ORIGINAL");
        root.put("terminalName", "TPV01");
        var issuer = root.putObject("issuer");
        issuer.put("headerPrimaryName", "Tienda Centro");
        issuer.put("headerSecondaryName", "Empresa");
        issuer.put("headerContactLine", "");
        issuer.put("headerAddressLine", "");
        issuer.put("email", "");
        return root;
    }

    private PDDocument render(String template, String outputName, ObjectNode model)
            throws Exception {
        var source = Files.readAllBytes(Path.of("..", "plantillas documentos", template));
        var compiled = new SafeJrxmlCompiler().compile(source).compiled();
        var parameters = new HashMap<String, Object>();
        parameters.put(JsonQueryExecuterFactory.JSON_INPUT_STREAM,
                new ByteArrayInputStream(mapper.writeValueAsBytes(model)));
        parameters.put("TPV_ISSUER_LOGO_PRESENT", false);
        var context = SafeJrxmlCompiler.secureContext();
        var print = JasperFillManager.getInstance(context).fill(
                new ByteArrayInputStream(compiled), parameters);
        byte[] pdfBytes = JasperExportManager.getInstance(context).exportToPdf(print);
        byte[] pngBytes = InvoiceJasperRenderer.ticketRaster(print);
        var previewDirectory = System.getProperty("tpv.test.printPreviewDirectory");
        if (previewDirectory != null && !previewDirectory.isBlank()) {
            var output = Path.of(previewDirectory);
            Files.createDirectories(output);
            Files.write(output.resolve(outputName + ".pdf"), pdfBytes);
            Files.write(output.resolve(outputName + ".png"), pngBytes);
        }
        return Loader.loadPDF(pdfBytes);
    }

    private static void assertFooterRows(PDDocument pdf) throws IOException {
        var positions = new FooterPositionStripper();
        var text = positions.getText(pdf);
        assertThat(text.indexOf("ATENDIDO POR: EMPLEADA ORIGINAL"))
                .isLessThan(text.indexOf("TERMINAL: TPV01"));
        assertThat(positions.x.get("ATENDIDO POR")).isNotNull();
        assertThat(positions.x.get("TERMINAL:")).isNotNull();
        assertThat(positions.x.get("ATENDIDO POR"))
                .isCloseTo(positions.x.get("TERMINAL:"), within(2f));
    }

    private static final class FooterPositionStripper extends PDFTextStripper {
        private final Map<String, Float> x = new HashMap<>();

        private FooterPositionStripper() throws IOException {
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions)
                throws IOException {
            if (!positions.isEmpty()) {
                for (String label : List.of("ATENDIDO POR", "TERMINAL:")) {
                    if (text.contains(label)) {
                        x.putIfAbsent(label, positions.get(0).getXDirAdj());
                    }
                }
            }
            super.writeString(text, positions);
        }
    }
}
