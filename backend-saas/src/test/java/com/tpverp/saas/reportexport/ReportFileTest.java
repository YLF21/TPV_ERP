package com.tpverp.saas.reportexport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportFileTest {
    private static final List<String> HEADERS = List.of("Producto", "Cantidad", "Código", "Vacío");
    private static final Map<String, String> CONTEXT = Map.of("UTC", "2026-11-18T12:00:00Z", "Filtro", "Tienda 1");
    @TempDir Path temp;

    @Test void csvNeutralizesFormulasAndPreservesPrecisionAndMissingValues() throws IOException {
        String csv = Files.readString(write("csv"));
        assertThat(csv).startsWith("\ufeff\"Producto\";\"Cantidad\";\"Código\";\"Vacío\"\r\n").contains("\"'=HYPERLINK(", "\"'9007199254740993.123\"", "\"'0012\"",
                "\"-0.001\"", "\"'\u200B @SUM(1)\"", "\"a;\"\"b\n\"", "UTC", "Tienda 1", ";\"\"\r\n",
                "\"'-9223372036854775808\"");
    }

    @Test void excelContainsValidXmlExactTextNoFormulasAndFrozenHeaders() throws Exception {
        var parts = new LinkedHashMap<String, String>();
        try (var zip = new ZipInputStream(Files.newInputStream(write("xlsx")))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) parts.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
        }
        var parser = DocumentBuilderFactory.newInstance();
        parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        for (String xml : parts.values()) parser.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertThat(parts).hasSize(7);
        assertThat(parts.get("xl/worksheets/sheet1.xml")).contains("9007199254740993.123", "-0.001", "0012",
                "inlineStr", "frozen", "中文 &amp; &lt;café&gt;", "A1:D4", "_x005F_x003D_", "&#13;",
                "r=\"B4\" s=\"4\" t=\"inlineStr", "customWidth=\"1\"", "<c r=\"D2\" s=\"2\"/>").doesNotContain("<f>", "Tienda 1");
        assertThat(parts.get("xl/worksheets/sheet2.xml")).contains("Tienda 1", "2026-11-18T12:00:00Z", ">Valor<");
        assertThat(parts.get("[Content_Types].xml")).contains("/xl/styles.xml", "spreadsheetml.styles+xml");
        assertThat(parts.get("xl/_rels/workbook.xml.rels")).contains("relationships/styles", "Target=\"styles.xml\"");
        assertThat(parts.get("xl/styles.xml")).contains("FF0B2A4C", "FFFFFFFF", "FFF0F4F8", "wrapText=\"1\"");
    }

    @Test void bothExcelLayoutsHaveStyledHeadersAlternatingRowsAndPrintSettings() throws Exception {
        for (boolean readable : List.of(true, false)) {
            Path path = temp.resolve("style-" + readable + ".xlsx");
            ReportFile.write(path, "xlsx", List.of("Fecha UTC", "Importe EUR", "Documentos", "Sin datos"),
                    CONTEXT, "Datos", "Contexto", "Valor", readable, writer -> {
                        writer.write(Arrays.asList("2026-11-18T12:00:00Z", "1234.5000", 1234, null));
                        writer.write(Arrays.asList("2026-11-19T12:00:00Z", "-0.001", 0, null));
                    });
            var parts = new LinkedHashMap<String, String>();
            try (var zip = new ZipInputStream(Files.newInputStream(path))) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    parts.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            assertThat(parts.get("xl/worksheets/sheet1.xml")).contains(
                    "showGridLines=\"0\"", "defaultRowHeight=\"22\"", "ht=\"44\"", "width=\"28\"",
                    "r=\"A1\" s=\"1\"", "r=\"A2\" s=\"2\"", "r=\"A3\" s=\"3\"",
                    "r=\"B2\" s=\"4\" t=\"inlineStr\"", ">1234.5000<", ">-0.001<",
                    "r=\"C2\" s=\"7\" t=\"n\"><v>1234</v>", "r=\"C3\" s=\"8\" t=\"n\"><v>0</v>",
                    "<c r=\"D2\" s=\"2\"/>", "<c r=\"D3\" s=\"3\"/>", "A1:D3",
                    "orientation=\"landscape\"", "fitToWidth=\"1\"");
            assertThat(parts.get("xl/worksheets/sheet2.xml")).contains("width=\"36\"", "width=\"82\"", "r=\"A2\" s=\"6\"");
        }
    }

    private Path write(String format) throws IOException {
        Path path = temp.resolve("test." + format);
        ReportFile.write(path, format, HEADERS, CONTEXT, "Datos", "Contexto", "Valor", true, writer -> {
            writer.write(Arrays.asList("=HYPERLINK(\"bad\")", "9007199254740993.123", "0012", null));
            writer.write(List.of("中文 & <café>", "-0.001", "\u200B @SUM(1)", "a;\"b\n"));
            writer.write(List.of("_x003D_\r\u0001\uFFFE", Long.MIN_VALUE));
        });
        return path;
    }
}
