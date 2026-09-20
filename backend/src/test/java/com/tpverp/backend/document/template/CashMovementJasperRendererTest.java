package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CashMovementJasperRendererTest {

    @TempDir Path temporaryDirectory;

    @ParameterizedTest
    @EnumSource(value = DocumentTemplateType.class, names = {"ENTRADA_CAJA", "RETIRADA_CAJA"})
    void rendersReadableCashReceiptWithLargeAmountAndWithdrawalSignatures(DocumentTemplateType type)
            throws Exception {
        String reason = type == DocumentTemplateType.RETIRADA_CAJA
                ? "Entrega de efectivo al responsable" : "Reposición del fondo de cambio";
        var rendered = render(type, "1.250,00 €", "Ana Caja", reason);
        try (var pdf = Loader.loadPDF(rendered.pdf())) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
            assertThat(pdf.getPage(0).getMediaBox().getWidth()).isEqualTo(227f);
            var text = new PDFTextStripper().getText(pdf).replaceAll("\\s+", " ");
            assertThat(text).contains("TIENDA DE PRUEBA", "20/09/2026 16:30", "1.250,00 €",
                    "Operador: Ana Caja", "Terminal: Caja 01", "Motivo: " + reason,
                    "Unidades de 50,00 €: 25")
                    .doesNotContain("Concepto", "Valor", "null", "Autorizador:", "Importe:");
            var amount = positions(pdf, "1.250,00 €");
            assertThat(amount).allSatisfy(position -> {
                assertThat(position.getFontSizeInPt()).isGreaterThanOrEqualTo(22f);
                assertThat(position.getFont().getName()).contains("Bold");
            });
            if (type == DocumentTemplateType.RETIRADA_CAJA) {
                assertThat(text).contains("RETIRADA DE EFECTIVO", "IMPORTE RETIRADO",
                        "Firma de quien entrega", "Firma de quien recibe");
                float giver = positions(pdf, "Firma de quien entrega").getFirst().getYDirAdj();
                float receiver = positions(pdf, "Firma de quien recibe").getFirst().getYDirAdj();
                assertThat(receiver - giver).isGreaterThanOrEqualTo(70f);
                float footer = positions(pdf, "Documento operativo.").getFirst().getYDirAdj();
                assertThat(footer - receiver).isGreaterThanOrEqualTo(70f);
            } else {
                assertThat(text).contains("ENTRADA DE EFECTIVO", "IMPORTE INGRESADO")
                        .doesNotContain("Firma de quien", "RETIRADA");
            }
        }
        var raster = ImageIO.read(new ByteArrayInputStream(rendered.ticketRasterPng()));
        assertThat(raster.getWidth()).isEqualTo(576);
        assertThat(raster.getHeight()).isBetween(400, 2000);
        var output = Path.of("target", "cash-receipt-verification");
        Files.createDirectories(output);
        String name = type == DocumentTemplateType.ENTRADA_CAJA ? "entrada-efectivo" : "retirada-efectivo";
        Files.write(output.resolve(name + ".pdf"), rendered.pdf());
        Files.write(output.resolve(name + ".png"), rendered.ticketRasterPng());
    }

    @ParameterizedTest
    @EnumSource(value = DocumentTemplateType.class, names = {"ENTRADA_CAJA", "RETIRADA_CAJA"})
    void wrapsLongOperatorAndReasonAndKeepsLargeAmountsComplete(DocumentTemplateType type)
            throws Exception {
        String operator = "Operador con un nombre de usuario muy largo para comprobar su impresion completa";
        String reason = "Retirada para la entrega de efectivo al responsable del turno. "
                + "Incluye billetes y monedas contados al finalizar la jornada y revisados antes de entregar. FIN MOTIVO";
        var rendered = render(type, "9.999.999,99 €", operator, reason);
        try (var pdf = Loader.loadPDF(rendered.pdf())) {
            String text = new PDFTextStripper().getText(pdf).replaceAll("\\s+", " ");
            assertThat(text).contains(operator, reason, "9.999.999,99 €", "Documento operativo.");
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
            if (type == DocumentTemplateType.RETIRADA_CAJA) {
                float endReason = positions(pdf, "FIN MOTIVO").getFirst().getYDirAdj();
                float giver = positions(pdf, "Firma de quien entrega").getFirst().getYDirAdj();
                assertThat(giver).isGreaterThan(endReason + 10f);
            }
        }
    }

    private InvoiceJasperRenderer.RenderedDocument render(DocumentTemplateType type,
            String amount, String operator, String reason) {
        var address = Map.of("linea1", "Calle Prueba 1", "codigoPostal", "35001",
                "ciudad", "Las Palmas", "provincia", "Las Palmas", "pais", "ES");
        var company = new Company("B12345678", "Empresa de prueba", address);
        var store = new Store(company, "001", "TIENDA DE PRUEBA", address, "cash-print-test",
                "Atlantic/Canary", "EUR", "es-ES");
        var mapper = new ObjectMapper();
        var compiler = new SafeJrxmlCompiler();
        var builtIns = new BuiltInDocumentJrxmlCatalog(compiler);
        var renderer = new InvoiceJasperRenderer(mock(DocumentTemplateRepository.class),
                new DocumentTemplateArtifactStorage(temporaryDirectory), compiler, mapper, builtIns);
        var data = mapper.createObjectNode();
        data.putObject("issuer").put("headerPrimaryName", store.getNombreEfectivo());
        data.putObject("movement").put("amountFormatted", amount)
                .put("createdAtFormatted", "20/09/2026 16:30");
        var lines = data.putArray("lines");
        lines.addObject().put("label", "Operador").put("value", operator);
        lines.addObject().put("label", "Terminal").put("value", "Caja 01");
        lines.addObject().put("label", "Importe").put("value", "1250.00");
        lines.addObject().put("label", "Autorizador").putNull("value");
        lines.addObject().put("label", "Motivo").put("value", reason);
        lines.addObject().put("label", "Unidades de 50,00 €").put("value", "25");
        return renderer.renderPayload(builtIns.reference(type, DocumentTemplateFormat.TICKET_80),
                type, DocumentTemplateFormat.TICKET_80, store, company, data, null).orElseThrow();
    }

    private static List<TextPosition> positions(PDDocument pdf, String expected) throws Exception {
        var matches = new ArrayList<TextPosition>();
        var stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> positions) {
                if (text.contains(expected)) matches.addAll(positions);
            }
        };
        stripper.getText(pdf);
        assertThat(matches).as("Printed text: %s", expected).isNotEmpty();
        return matches;
    }
}
