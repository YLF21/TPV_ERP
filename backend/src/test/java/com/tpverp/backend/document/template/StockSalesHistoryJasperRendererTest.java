package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tpverp.backend.document.InvoicePresentationSnapshot;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.util.JRLoader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StockSalesHistoryJasperRendererTest {

    private static final List<String> ALL_COLUMNS = List.of("occurredAt", "document", "status", "customer",
            "quantity", "unitPrice", "discount", "total", "user", "store", "warehouse");
    private static final Map<String, String> ADDRESS = Map.of("linea1", "Calle Ficticia 1", "ciudad", "Las Palmas",
            "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
    private final ObjectMapper mapper = new ObjectMapper();
    private final SafeJrxmlCompiler compiler = new SafeJrxmlCompiler();
    private final BuiltInDocumentJrxmlCatalog catalog = new BuiltInDocumentJrxmlCatalog(compiler);
    private final Company company = new Company("B00000000", "EMPRESA FICTICIA", ADDRESS);
    private final Store store = new Store(company, "001", "Tienda ficticia", ADDRESS,
            UUID.randomUUID().toString(), "Atlantic/Canary", "EUR", "es-ES");
    @TempDir Path temporaryDirectory;

    @Test
    void rendersAllSelectedColumnsAndCompleteRowsAcrossPages() throws Exception {
        byte[] bytes = render(data(65, ALL_COLUMNS));
        try (var pdf = Loader.loadPDF(bytes)) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(2);
            var allText = new PDFTextStripper().getText(pdf).replaceAll("\\s+", " ");
            assertThat(allText).contains("PRODUCTO FICTICIO", "Desde: 01/09/2026", "Estado: Todos",
                    "REG-000", "REG-064", "FINAL CLIENTE", "-2,125", "12,345", "7,5", "-24,69");
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                var stripper = new PDFTextStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                assertThat(stripper.getText(pdf)).contains("Documento", "Cliente", "Cantidad", "Precio",
                        "Dto. %", "Total", "Usuario", "Tienda", "Almacén", "Página " + page);
            }
            var positions = positions(pdf);
            assertThat(positions).allSatisfy(position -> {
                assertThat(position.getXDirAdj()).isGreaterThanOrEqualTo(19f);
                assertThat(position.getXDirAdj() + position.getWidthDirAdj()).isLessThanOrEqualTo(823f);
                assertThat(position.getYDirAdj()).isLessThan(576f);
            });
        }
        writeFixture("historial-f6-multipagina.pdf", bytes);
    }

    @Test
    void expandsAndOrdersSelectedColumnsWithoutLeakingPreviousLayout() throws Exception {
        byte[] selected = render(data(1, List.of("total", "document", "customer")));
        try (var pdf = Loader.loadPDF(selected)) {
            var text = new PDFTextStripper().getText(pdf).replaceAll("\\s+", " ");
            assertThat(text).contains("Total", "Documento", "Cliente", "-24,69")
                    .doesNotContain("Cantidad", "Precio", "Dto. %", "Usuario", "ALMACEN FICTICIO");
            assertThat(xOf(pdf, "Total")).isLessThan(xOf(pdf, "Documento"));
            assertThat(xOf(pdf, "Documento")).isLessThan(xOf(pdf, "Cliente"));
        }
        try (var pdf = Loader.loadPDF(render(data(1, ALL_COLUMNS)))) {
            assertThat(xOf(pdf, "Documento")).isLessThan(xOf(pdf, "Total"));
            assertThat(new PDFTextStripper().getText(pdf).replaceAll("\\s+", " "))
                    .contains("Cantidad", "ALMACEN FICTICIO");
        }
        writeFixture("historial-f6-columnas.pdf", selected);
    }

    @Test
    void emptyHistoryStillRendersProductFiltersAndColumnHeaders() throws Exception {
        byte[] bytes = render(data(0, List.of("document", "quantity", "total")));
        try (var pdf = Loader.loadPDF(bytes)) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
            assertThat(new PDFTextStripper().getText(pdf)).contains("PRODUCTO FICTICIO",
                    "Desde: 01/09/2026", "Documento", "Cantidad", "Total", "Sin movimientos");
        }
        writeFixture("historial-f6-vacio.pdf", bytes);
    }

    @Test
    void leavesCustomHistoryWithoutOptInKeysUnchanged() throws Exception {
        var compiled = compiler.compile(customTemplate("").getBytes(StandardCharsets.UTF_8)).compiled();
        var parameters = new LinkedHashMap<String, Object>();
        var report = StockSalesHistoryJasperLayout.prepare(compiled, data(1, ALL_COLUMNS), parameters);
        assertThat(report.getColumnHeader().getElements()[0].getX()).isEqualTo(37);
        assertThat(report.getColumnHeader().getElements()[0].getWidth()).isEqualTo(100);
        assertThat(parameters).isEmpty();
        try (var pdf = Loader.loadPDF(renderCustom(compiled, DocumentTemplateType.HISTORIAL_VENTAS_PRODUCTO))) {
            assertThat(xOf(pdf, "CUSTOM_LAYOUT")).isEqualTo(57f);
            assertThat(new PDFTextStripper().getText(pdf)).contains("2026-09-19T09:00:00Z");
        }
    }

    @Test
    void adaptsOnlyHistoryAndSupportsPartialOptInTemplates() throws Exception {
        var compiled = compiler.compile(customTemplate("history.column.warehouse")
                .getBytes(StandardCharsets.UTF_8)).compiled();
        var parameters = new LinkedHashMap<String, Object>();
        var report = StockSalesHistoryJasperLayout.prepare(compiled,
                data(1, List.of("total")), parameters);
        assertThat(report.getColumnHeader().getElements()[0].getX()).isZero();
        assertThat(report.getColumnHeader().getElements()[0].getWidth()).isEqualTo(802);
        assertThat(parameters).containsEntry("TPV_HISTORY_warehouse", true);
        var unchanged = (JasperReport) JRLoader.loadObject(new ByteArrayInputStream(compiled));
        assertThat(unchanged.getColumnHeader().getElements()[0].getWidth()).isEqualTo(100);
        try (var pdf = Loader.loadPDF(renderCustom(compiled, DocumentTemplateType.ALBARAN_VENTA))) {
            assertThat(xOf(pdf, "CUSTOM_LAYOUT")).isEqualTo(57f);
        }
    }

    private byte[] render(ObjectNode data) {
        return renderer(catalog).renderPayload(catalog.reference(DocumentTemplateType.HISTORIAL_VENTAS_PRODUCTO,
                DocumentTemplateFormat.A4), DocumentTemplateType.HISTORIAL_VENTAS_PRODUCTO,
                DocumentTemplateFormat.A4, store, company, data, null).orElseThrow().pdf();
    }

    private byte[] renderCustom(byte[] compiled, DocumentTemplateType type) {
        var customCatalog = mock(BuiltInDocumentJrxmlCatalog.class);
        when(customCatalog.compiled(any(), any(), any())).thenReturn(compiled);
        var reference = new InvoicePresentationSnapshot.TemplateReference(null, "CUSTOM", 1, 1, "a".repeat(64), true);
        return renderer(customCatalog).renderPayload(reference, type, DocumentTemplateFormat.A4,
                store, company, data(1, List.of("total")), null).orElseThrow().pdf();
    }

    private InvoiceJasperRenderer renderer(BuiltInDocumentJrxmlCatalog templates) {
        return new InvoiceJasperRenderer(mock(DocumentTemplateRepository.class),
                new DocumentTemplateArtifactStorage(temporaryDirectory), compiler, mapper, templates);
    }

    private ObjectNode data(int count, List<String> columns) {
        var data = mapper.createObjectNode();
        data.putObject("issuer").put("details", "EMPRESA FICTICIA · Producto: PROD-001 · PRODUCTO FICTICIO");
        data.putObject("document").put("concept", "Desde: 01/09/2026 · Hasta: 19/09/2026 · Estado: Todos");
        columns.forEach(data.putArray("visibleColumns")::add);
        var lines = data.putArray("lines");
        for (int index = 0; index < count; index++) {
            var line = lines.addObject();
            line.put("date", "2026-09-19T09:00:00Z");
            line.put("occurredAtLabel", "19/09/2026 10:00");
            line.put("document", "REG-%03d".formatted(index));
            line.put("status", index % 2 == 0 ? "CONFIRMADO" : "ANULADO");
            line.put("customer", "Cliente ficticio con nombre largo para comprobar el salto de línea FINAL CLIENTE");
            line.put("quantity", new BigDecimal("-2.125"));
            line.put("unitPrice", new BigDecimal("12.345"));
            line.put("discount", new BigDecimal("7.5"));
            line.put("total", new BigDecimal("-24.69"));
            line.put("user", "USUARIO FICTICIO");
            line.put("store", "TIENDA FICTICIA");
            line.put("warehouse", "ALMACEN FICTICIO");
        }
        return data;
    }

    private static String customTemplate(String key) {
        return """
                <jasperReport name="custom_history" language="java" pageWidth="842" pageHeight="595"
                  columnWidth="802" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
                  <query language="jsonql"><![CDATA[lines]]></query>
                  <field name="date" class="java.lang.String"><property name="net.sf.jasperreports.jsonql.field.expression" value="date"/></field>
                  <columnHeader height="24"><element kind="staticText" key="%s" x="37" y="0" width="100" height="24" fontSize="8">
                    <text><![CDATA[CUSTOM_LAYOUT]]></text></element></columnHeader>
                  <detail><band height="24"><element kind="textField" x="37" y="0" width="200" height="24">
                    <expression><![CDATA[$F{date}]]></expression></element></band></detail>
                </jasperReport>
                """.formatted(key);
    }

    private static List<TextPosition> positions(PDDocument pdf) throws Exception {
        var positions = new java.util.ArrayList<TextPosition>();
        new PDFTextStripper() {
            @Override protected void writeString(String text, List<TextPosition> characters) {
                positions.addAll(characters);
            }
        }.getText(pdf);
        return positions;
    }

    private static float xOf(PDDocument pdf, String expected) throws Exception {
        var found = new java.util.ArrayList<Float>();
        new PDFTextStripper() {
            @Override protected void writeString(String text, List<TextPosition> characters) {
                int index = text.indexOf(expected);
                if (index >= 0) found.add(characters.get(index).getXDirAdj());
            }
        }.getText(pdf);
        assertThat(found).as("PDF text: %s", expected).isNotEmpty();
        return found.getFirst();
    }

    private static void writeFixture(String fileName, byte[] bytes) throws Exception {
        String outputDirectory = System.getProperty("tpv.history.pdfOutputDir");
        if (outputDirectory == null || outputDirectory.isBlank()) return;
        var directory = Path.of(outputDirectory);
        Files.createDirectories(directory);
        Files.write(directory.resolve(fileName), bytes);
    }
}
