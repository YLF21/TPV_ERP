package com.tpverp.saas.reportexport;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes CSV and OOXML a row at a time, retaining exact decimal and identifier strings. */
final class ReportFile {
    static final int XLSX_COLUMN_LIMIT = 16_384;
    private static final long EXACT_INTEGER_LIMIT = 1_000_000_000_000_000L;
    private static final Pattern DECIMAL = Pattern.compile("-?[0-9]+(?:\\.[0-9]+)?");
    private static final Pattern LEADING_ZERO = Pattern.compile("-?0[0-9]+(?:\\.[0-9]+)?");
    private static final Pattern EXCEL_ESCAPE = Pattern.compile("_[xX][0-9a-fA-F]{4}_");
    private static final String SHEET_OPEN = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetPr><tabColor rgb="FF0B2A4C"/><pageSetUpPr fitToPage="1"/></sheetPr><sheetViews><sheetView workbookViewId="0" showGridLines="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/><selection pane="bottomLeft" activeCell="A2" sqref="A2"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight="22"/>
            """;
    // Styles change presentation only: decimal strings and identifiers retain their exact stored values.
    private static final String STYLES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <fonts count="3">
                <font><sz val="11"/><color rgb="FF24364B"/><name val="Calibri"/><family val="2"/></font>
                <font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/><family val="2"/></font>
                <font><b/><sz val="11"/><color rgb="FF0B2A4C"/><name val="Calibri"/><family val="2"/></font>
              </fonts>
              <fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF0B2A4C"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFF0F4F8"/><bgColor indexed="64"/></patternFill></fill></fills>
              <borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FF64748B"/></left><right style="thin"><color rgb="FF64748B"/></right><top style="thin"><color rgb="FF64748B"/></top><bottom style="thin"><color rgb="FF64748B"/></bottom><diagonal/></border></borders>
              <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
              <cellXfs count="9">
                <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
                <xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="left" vertical="center" wrapText="1" indent="1"/></xf>
                <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment horizontal="left" vertical="center" wrapText="1" indent="1"/></xf>
                <xf numFmtId="0" fontId="0" fillId="3" borderId="1" xfId="0" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="left" vertical="center" wrapText="1" indent="1"/></xf>
                <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center" wrapText="1" indent="1"/></xf>
                <xf numFmtId="0" fontId="0" fillId="3" borderId="1" xfId="0" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center" wrapText="1" indent="1"/></xf>
                <xf numFmtId="0" fontId="2" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="left" vertical="center" wrapText="1" indent="1"/></xf>
                <xf numFmtId="3" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center" wrapText="1" indent="1"/></xf>
                <xf numFmtId="3" fontId="0" fillId="3" borderId="1" xfId="0" applyNumberFormat="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center" wrapText="1" indent="1"/></xf>
              </cellXfs>
              <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
            </styleSheet>
            """;

    private ReportFile() {}

    @FunctionalInterface
    interface RowWriter {
        void write(List<?> values) throws IOException;
    }

    @FunctionalInterface
    interface Rows {
        void writeTo(RowWriter writer) throws IOException;
    }

    static void write(Path path, String format, List<String> headers, Map<String, String> context,
            String dataLabel, String contextLabel, String valueLabel, boolean readable, Rows rows) throws IOException {
        if (format.equals("csv")) {
            csv(path, headers, context, readable, rows);
        } else {
            xlsx(path, headers, context, dataLabel, contextLabel, valueLabel, readable, rows);
        }
    }

    private static void csv(Path path, List<String> headers, Map<String, String> context, boolean readable, Rows rows)
            throws IOException {
        try (var out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write('\ufeff');
            if (readable) {
                line(out, headers);
                rows.writeTo(row -> line(out, row));
                out.write("\r\n");
            }
            for (var entry : context.entrySet()) {
                line(out, List.of("# " + entry.getKey(), entry.getValue()));
            }
            if (!readable) {
                out.write("\r\n");
                line(out, headers);
                rows.writeTo(row -> line(out, row));
            }
        }
    }

    private static void line(Writer out, List<?> row) throws IOException {
        for (int index = 0; index < row.size(); index++) {
            if (index > 0) {
                out.write(';');
            }
            Object cell = row.get(index);
            String value = cell == null ? "" : cell.toString();
            out.write('"');
            if (needsTextPrefix(value)) {
                out.write('\'');
            }
            out.write(value.replace("\"", "\"\""));
            out.write('"');
        }
        out.write("\r\n");
    }

    private static boolean needsTextPrefix(String value) {
        int start = 0;
        while (start < value.length()) {
            int codePoint = value.codePointAt(start);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)
                    && !Character.isISOControl(codePoint) && Character.getType(codePoint) != Character.FORMAT) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        if (start == value.length()) {
            return false;
        }
        String trimmed = value.substring(start);
        if (DECIMAL.matcher(trimmed).matches()) {
            long digits = trimmed.chars().filter(Character::isDigit).count();
            return digits > 15 || LEADING_ZERO.matcher(trimmed).matches();
        }
        return "=+@-＝＋＠－".indexOf(trimmed.charAt(0)) >= 0;
    }

    private static void xlsx(Path path, List<String> headers, Map<String, String> context,
            String dataLabel, String contextLabel, String valueLabel, boolean readable, Rows rows) throws IOException {
        try (var zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(path)),
                StandardCharsets.UTF_8)) {
            entry(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>
                    """.replace("</Types>", "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>"));
            entry(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>
                    """);
            entry(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="%s" sheetId="1" r:id="rId1"/><sheet name="%s" sheetId="2" r:id="rId2"/></sheets></workbook>
                    """.formatted(dataLabel, contextLabel));
            entry(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/></Relationships>
                    """.replace("</Relationships>", "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>"));
            entry(zip, "xl/styles.xml", STYLES);
            var out = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
            sheet(zip, out, "sheet1", headers, rows);
            sheet(zip, out, "sheet2", List.of(contextLabel, valueLabel), writer -> {
                for (var entry : context.entrySet()) {
                    writer.write(List.of(entry.getKey(), entry.getValue()));
                }
            });
        }
    }

    private static void sheet(ZipOutputStream zip, Writer out, String name, List<String> headers, Rows rows)
            throws IOException {
        zip.putNextEntry(new ZipEntry("xl/worksheets/" + name + ".xml"));
        out.write(SHEET_OPEN);
        boolean contextSheet = name.equals("sheet2");
        if (!headers.isEmpty()) {
            out.write("<cols>");
            for (int i = 0; i < headers.size(); i++) {
                int width = contextSheet ? (i == 0 ? 36 : 82) : Math.clamp(headers.get(i).length() + 4, 28, 42);
                out.write("<col min=\"" + (i + 1) + "\" max=\"" + (i + 1) + "\" width=\"" + width + "\" customWidth=\"1\"/>");
            }
            out.write("</cols>");
        }
        out.write("<sheetData>");
        var sheet = new Sheet(out, headers.size(), contextSheet);
        sheet.write(headers);
        rows.writeTo(sheet);
        out.write("</sheetData>");
        if (!headers.isEmpty()) {
            out.write("<autoFilter ref=\"A1:" + column(headers.size() - 1) + sheet.row + "\"/>");
        }
        out.write("<printOptions horizontalCentered=\"1\"/><pageMargins left=\"0.3\" right=\"0.3\" top=\"0.5\" bottom=\"0.5\" header=\"0.2\" footer=\"0.2\"/><pageSetup paperSize=\"9\" orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/><headerFooter><oddFooter>&amp;LesPOS&amp;R&amp;P / &amp;N</oddFooter></headerFooter></worksheet>");
        out.flush();
        zip.closeEntry();
    }

    private static final class Sheet implements RowWriter {
        private final Writer out;
        private final List<String> columns;
        private final boolean contextSheet;
        private int row;

        private Sheet(Writer out, int width, boolean contextSheet) {
            this.out = out;
            this.columns = java.util.stream.IntStream.range(0, width).mapToObj(ReportFile::column).toList();
            this.contextSheet = contextSheet;
        }

        @Override
        public void write(List<?> values) throws IOException {
            row++;
            String rowNumber = Integer.toString(row);
            out.write("<row r=\"");
            out.write(rowNumber);
            out.write(row == 1 ? "\" ht=\"44\" customHeight=\"1\">" : "\">");
            for (int index = 0; index < columns.size(); index++) {
                Object value = index < values.size() ? values.get(index) : null;
                boolean number = value instanceof Integer || value instanceof Long integer
                        && integer > -EXACT_INTEGER_LIMIT && integer < EXACT_INTEGER_LIMIT;
                int stripe = row % 2 == 0 ? 0 : 1;
                int style = row == 1 ? 1 : contextSheet && index == 0 ? 6
                        : number ? 7 + stripe
                        : value != null && DECIMAL.matcher(value.toString()).matches() ? 4 + stripe : 2 + stripe;
                out.write("<c r=\"");
                out.write(columns.get(index));
                out.write(rowNumber);
                out.write("\" s=\"" + style);
                if (value == null) {
                    out.write("\"/>");
                } else if (number) {
                    out.write("\" t=\"n\"><v>");
                    out.write(value.toString());
                    out.write("</v></c>");
                } else {
                    out.write("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">");
                    writeXmlText(out, value.toString());
                    out.write("</t></is></c>");
                }
            }
            out.write("</row>");
        }
    }

    private static String column(int index) {
        var name = new StringBuilder();
        do {
            name.insert(0, (char) ('A' + index % 26));
            index = index / 26 - 1;
        } while (index >= 0);
        return name.toString();
    }

    private static void writeXmlText(Writer out, String value) throws IOException {
        var escapes = EXCEL_ESCAPE.matcher(value);
        if (escapes.find()) {
            value = escapes.replaceAll(match -> "_x005F_" + match.group().substring(1));
        }
        for (int index = 0; index < value.length();) {
            int character = value.codePointAt(index);
            switch (character) {
                case '&' -> out.write("&amp;");
                case '<' -> out.write("&lt;");
                case '>' -> out.write("&gt;");
                case '\r' -> out.write("&#13;");
                default -> {
                    if (character == '\t' || character == '\n' || character >= 0x20 && character <= 0xD7FF
                            || character >= 0xE000 && character <= 0xFFFD || character >= 0x10000) {
                        out.write(value, index, Character.charCount(character));
                    }
                }
            }
            index += Character.charCount(character);
        }
    }

    private static void entry(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
