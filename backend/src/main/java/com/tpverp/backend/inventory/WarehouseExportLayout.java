package com.tpverp.backend.inventory;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Common document presentation for warehouse exports. Values and calculations belong to callers. */
public final class WarehouseExportLayout {
    private static final XSSFColor NAVY = color(10, 47, 86);
    private static final XSSFColor PALE = color(234, 241, 247);
    private static final XSSFColor STRIPE = color(246, 249, 252);
    private static final XSSFColor WHITE = color(255, 255, 255);
    private static final float[] PDF_NAVY = {10 / 255f, 47 / 255f, 86 / 255f};

    private WarehouseExportLayout() { }

    public record MetadataField(String label, String value) { }

    public static List<MetadataField> organizationMetadata(Store store, Company company, String locale, Instant issuedAt) {
        if (store == null) return List.of();
        String address = address(store.getDireccion());
        String issued = issuedAt == null ? "" : issuedAt.atZone(ZoneId.of(
                        store.getTimezone() == null ? "UTC" : store.getTimezone()))
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        return List.of(new MetadataField(tr(locale, "Empresa", "Company", "公司"), company == null ? "" : company.getRazonSocial()),
                new MetadataField(tr(locale, "NIF", "Tax ID", "税号"), company == null ? "" : company.getTaxId()),
                new MetadataField(tr(locale, "Tienda", "Store", "门店"), store.getNombreEfectivo()),
                new MetadataField(tr(locale, "Dirección", "Address", "地址"), address),
                new MetadataField(tr(locale, "Emitido", "Issued", "生成时间"), issued));
    }

    public static String address(Map<String, String> address) {
        if (address == null) return "";
        var parts = new ArrayList<String>();
        for (String key : List.of("linea1", "linea2", "codigoPostal", "ciudad", "provincia", "pais")) {
            String value = address.get(key);
            if (value != null && !value.isBlank()) parts.add(value);
        }
        return String.join(", ", parts);
    }

    public static String tr(String locale, String es, String en, String zh) {
        String language = locale == null ? "es" : locale.toLowerCase(Locale.ROOT);
        return language.startsWith("zh") ? zh : language.startsWith("en") ? en : es;
    }

    public record WorkbookLayout(int headerRow, CellStyle title, CellStyle header,
            CellStyle metaLabel, CellStyle metaValue, CellStyle text, CellStyle textStripe,
            CellStyle number, CellStyle numberStripe, CellStyle money, CellStyle moneyStripe,
            CellStyle money3, CellStyle money3Stripe, CellStyle percent, CellStyle percentStripe,
            CellStyle totalLabel, CellStyle totalNumber, CellStyle totalMoney,
            CellStyle totalMoney3, CellStyle totalPercent) {
        public CellStyle text(boolean stripe) { return stripe ? textStripe : text; }
        public CellStyle number(boolean stripe) { return stripe ? numberStripe : number; }
        public CellStyle money(boolean stripe) { return stripe ? moneyStripe : money; }
        public CellStyle money3(boolean stripe) { return stripe ? money3Stripe : money3; }
        public CellStyle percent(boolean stripe) { return stripe ? percentStripe : percent; }
    }

    public static WorkbookLayout workbook(XSSFWorkbook book, Sheet sheet, String title,
            List<MetadataField> metadata, List<String> headers, int[] widths) {
        return workbook(book, sheet, title, metadata, headers, widths, "es");
    }

    public static WorkbookLayout workbook(XSSFWorkbook book, Sheet sheet, String title,
            List<MetadataField> metadata, List<String> headers, int[] widths, String locale) {
        return workbook(book, sheet, title, metadata, headers, widths, locale, null);
    }

    public static WorkbookLayout workbook(XSSFWorkbook book, Sheet sheet, String title,
            List<MetadataField> metadata, List<String> headers, int[] widths, String locale, String currency) {
        if (headers.isEmpty() || widths.length != headers.size()) throw new IllegalArgumentException("Columnas inválidas");
        book.getFontAt(0).setFontName("Calibri");
        CellStyle titleStyle = style(book, NAVY, WHITE, 18, true, null);
        CellStyle header = style(book, NAVY, WHITE, 10, true, null);
        CellStyle metaLabel = style(book, PALE, NAVY, 10, true, null);
        CellStyle metaValue = style(book, PALE, NAVY, 10, false, null);
        CellStyle text = style(book, WHITE, NAVY, 10, false, null);
        CellStyle textStripe = style(book, STRIPE, NAVY, 10, false, null);
        CellStyle number = style(book, WHITE, NAVY, 10, false, "#,##0.###");
        CellStyle numberStripe = style(book, STRIPE, NAVY, 10, false, "#,##0.###");
        CellStyle money = style(book, WHITE, NAVY, 10, false, currencyFormat("#,##0.00", currency));
        CellStyle moneyStripe = style(book, STRIPE, NAVY, 10, false, currencyFormat("#,##0.00", currency));
        CellStyle money3 = style(book, WHITE, NAVY, 10, false, currencyFormat("#,##0.00#", currency));
        CellStyle money3Stripe = style(book, STRIPE, NAVY, 10, false, currencyFormat("#,##0.00#", currency));
        CellStyle percent = style(book, WHITE, NAVY, 10, false, "0.00\"%\"");
        CellStyle percentStripe = style(book, STRIPE, NAVY, 10, false, "0.00\"%\"");
        CellStyle totalLabel = style(book, NAVY, WHITE, 13, true, null);
        CellStyle totalNumber = style(book, NAVY, WHITE, 13, true, "#,##0.###");
        CellStyle totalMoney = style(book, NAVY, WHITE, 13, true, currencyFormat("#,##0.00", currency));
        CellStyle totalMoney3 = style(book, NAVY, WHITE, 13, true, currencyFormat("#,##0.00#", currency));
        CellStyle totalPercent = style(book, NAVY, WHITE, 13, true, "0.00\"%\"");
        for (int c = 0; c < widths.length; c++) sheet.setColumnWidth(c, Math.min(255 * 256, widths[c] * 256));
        Row heading = sheet.createRow(0);
        heading.setHeightInPoints(36);
        filled(heading, headers.size(), titleStyle);
        heading.getCell(0).setCellValue(title);
        if (headers.size() > 1) sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, headers.size() - 1));
        int rowNumber = 1;
        for (MetadataField field : metadata) {
            Row row = sheet.createRow(rowNumber++);
            row.setHeightInPoints(Math.max(27, Math.min(180, 15 * ((Objects.toString(field.value(), "").length() / 85) + 2))));
            filled(row, headers.size(), metaValue);
            row.getCell(0).setCellStyle(metaLabel);
            if (headers.size() == 1) row.getCell(0).setCellValue(field.label() + ": " + Objects.toString(field.value(), ""));
            else {
                row.getCell(0).setCellValue(field.label());
                row.getCell(1).setCellValue(Objects.toString(field.value(), ""));
                if (headers.size() > 2) sheet.addMergedRegion(new CellRangeAddress(row.getRowNum(), row.getRowNum(), 1, headers.size() - 1));
            }
        }
        sheet.createRow(rowNumber++).setHeightInPoints(9);
        Row tableHeader = sheet.createRow(rowNumber);
        tableHeader.setHeightInPoints(31);
        filled(tableHeader, headers.size(), header);
        for (int c = 0; c < headers.size(); c++) tableHeader.getCell(c).setCellValue(headers.get(c));
        sheet.createFreezePane(0, rowNumber + 1);
        sheet.setRepeatingRows(new CellRangeAddress(rowNumber, rowNumber, -1, -1));
        sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
        sheet.getPrintSetup().setLandscape(headers.size() > 5);
        sheet.getPrintSetup().setFitWidth((short) 1);
        sheet.getPrintSetup().setFitHeight((short) 0);
        sheet.setFitToPage(true);
        sheet.getFooter().setRight(tr(locale, "Página &P de &N", "Page &P of &N", "第 &P / &N 页"));
        return new WorkbookLayout(rowNumber, titleStyle, header, metaLabel, metaValue, text, textStripe,
                number, numberStripe, money, moneyStripe, money3, money3Stripe, percent, percentStripe,
                totalLabel, totalNumber, totalMoney, totalMoney3, totalPercent);
    }

    public static void bodyRow(Row row, WorkbookLayout styles, int index) {
        boolean stripe = index % 2 == 1;
        int lines = 1;
        for (var cell : row) {
            if (cell.getCellStyle() == null || cell.getCellStyle().getIndex() == 0) cell.setCellStyle(styles.text(stripe));
            if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                int characters = Math.max(1, row.getSheet().getColumnWidth(cell.getColumnIndex()) / 256 - 2);
                int cellLines = 0;
                for (String segment : cell.getStringCellValue().split("\\R", -1))
                    cellLines += Math.max(1, (segment.length() + characters - 1) / characters);
                lines = Math.max(lines, cellLines);
            }
        }
        row.setHeightInPoints(Math.min(409, Math.max(25, lines * 15 + 8)));
    }

    public static void autoFilter(Sheet sheet, int headerRow, int lastBodyRow, int columns) {
        sheet.setAutoFilter(new CellRangeAddress(headerRow, Math.max(headerRow, lastBodyRow), 0, columns - 1));
    }

    private static void filled(Row row, int count, CellStyle style) {
        for (int c = 0; c < count; c++) row.createCell(c).setCellStyle(style);
    }

    private static CellStyle style(XSSFWorkbook book, XSSFColor fill, XSSFColor ink,
            int size, boolean bold, String format) {
        XSSFCellStyle style = book.createCellStyle();
        style.setFillForegroundColor(fill);
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        XSSFFont font = book.createFont();
        font.setFontName("Calibri");
        font.setFontHeightInPoints((short) size);
        font.setBold(bold);
        font.setColor(ink);
        style.setFont(font);
        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setAlignment(format == null ? HorizontalAlignment.LEFT : HorizontalAlignment.RIGHT);
        if (format != null) style.setDataFormat(book.createDataFormat().getFormat(format));
        return style;
    }

    private static XSSFColor color(int red, int green, int blue) {
        return new XSSFColor(new byte[] {(byte) red, (byte) green, (byte) blue}, null);
    }

    private static String currencyFormat(String decimalPattern, String currency) {
        if (currency == null || currency.isBlank()) return decimalPattern;
        String label = switch (currency.toUpperCase(Locale.ROOT)) {
            case "EUR" -> "€";
            case "USD" -> "$";
            case "CNY" -> "¥";
            default -> currency.matches("[A-Za-z]{3}") ? currency.toUpperCase(Locale.ROOT) : "";
        };
        return label.isEmpty() ? decimalPattern : decimalPattern + " \"" + label + "\"";
    }

    public record PdfRow(List<String> values, int accentColumn, int sign) {
        public PdfRow(List<String> values) { this(values, -1, 0); }
    }

    public record PdfSpec(String title, String locale, List<MetadataField> metadata,
            List<String> headers, float[] weights, List<PdfRow> rows) { }

    public static byte[] pdf(PdfSpec spec) throws IOException {
        if (spec.headers().isEmpty() || spec.headers().size() != spec.weights().length)
            throw new IllegalArgumentException("Columnas inválidas");
        PDRectangle pageSize = spec.headers().size() <= 5 ? PDRectangle.A4
                : new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
        float margin = 28, available = pageSize.getWidth() - 2 * margin;
        float totalWeight = 0;
        for (float weight : spec.weights()) totalWeight += weight;
        float[] widths = new float[spec.weights().length];
        for (int i = 0; i < widths.length; i++) widths[i] = available * spec.weights()[i] / totalWeight;
        float fontSize = spec.headers().size() > 10 ? 6.2f : 8f;
        float lineHeight = fontSize + 3.2f;
        try (var doc = new PDDocument(); var output = new ByteArrayOutputStream()) {
            boolean unicode = (spec.locale() != null && spec.locale().startsWith("zh"))
                    || spec.title().codePoints().anyMatch(point -> point > 255)
                    || spec.headers().stream().anyMatch(value -> value.codePoints().anyMatch(point -> point > 255))
                    || spec.metadata().stream().anyMatch(field -> Objects.toString(field.label(), "").codePoints().anyMatch(point -> point > 255)
                            || Objects.toString(field.value(), "").codePoints().anyMatch(point -> point > 255))
                    || spec.rows().stream().flatMap(row -> row.values().stream()).anyMatch(value -> value.codePoints().anyMatch(point -> point > 255));
            var fonts = new PdfFonts(new PDType1Font(Standard14Fonts.FontName.HELVETICA),
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), font(doc, unicode));
            PDPageContentStream stream = null;
            float y = 0;
            int rowIndex = 0;
            try {
                List<PdfRow> rows = spec.rows().isEmpty()
                        ? List.of(new PdfRow(java.util.Collections.nCopies(widths.length, ""))) : spec.rows();
                for (PdfRow row : rows) {
                    var cells = new ArrayList<List<String>>();
                    for (int c = 0; c < widths.length; c++) cells.add(wrap(fonts, row.values().get(c), widths[c] - 16, fontSize, false));
                    int lineCount = cells.stream().mapToInt(List::size).max().orElse(1);
                    int offset = 0;
                    while (offset < lineCount) {
                        if (stream == null || y < 58 + lineHeight) {
                            if (stream != null) stream.close();
                            var page = new PDPage(pageSize);
                            doc.addPage(page);
                            stream = new PDPageContentStream(doc, page);
                            title(stream, fonts, pageSize, margin, available, spec.title());
                            y = pageSize.getHeight() - 81;
                            if (doc.getNumberOfPages() == 1) {
                                for (MetadataField field : spec.metadata()) {
                                    var lines = wrap(fonts, field.label() + ": " + Objects.toString(field.value(), ""), available, 8, false);
                                    for (String line : lines) {
                                        if (y < 95) {
                                            stream.close();
                                            var continuation = new PDPage(pageSize);
                                            doc.addPage(continuation);
                                            stream = new PDPageContentStream(doc, continuation);
                                            title(stream, fonts, pageSize, margin, available, spec.title());
                                            y = pageSize.getHeight() - 81;
                                        }
                                        text(stream, fonts, false, 8, margin, y, line, 0, 0, 0);
                                        y -= 11;
                                    }
                                }
                                y -= 9;
                                if (y < 95) {
                                    stream.close();
                                    var continuation = new PDPage(pageSize);
                                    doc.addPage(continuation);
                                    stream = new PDPageContentStream(doc, continuation);
                                    title(stream, fonts, pageSize, margin, available, spec.title());
                                    y = pageSize.getHeight() - 81;
                                }
                            } else y -= 12;
                            int headerLines = 1;
                            var headingCells = new ArrayList<List<String>>();
                            for (int c = 0; c < widths.length; c++) {
                                var lines = wrap(fonts, spec.headers().get(c), widths[c] - 16, fontSize, true);
                                headingCells.add(lines);
                                headerLines = Math.max(headerLines, lines.size());
                            }
                            float headerHeight = Math.max(26, headerLines * lineHeight + 16);
                            stream.setNonStrokingColor(PDF_NAVY[0], PDF_NAVY[1], PDF_NAVY[2]);
                            stream.addRect(margin, y - headerHeight, available, headerHeight);
                            stream.fill();
                            float x = margin;
                            for (int c = 0; c < widths.length; c++) {
                                for (int line = 0; line < headingCells.get(c).size(); line++)
                                    text(stream, fonts, true, fontSize, x + 8, y - 8 - (line + 1) * lineHeight + fontSize,
                                            headingCells.get(c).get(line), 1, 1, 1);
                                x += widths[c];
                            }
                            y -= headerHeight;
                        }
                        int count = Math.min(lineCount - offset, Math.max(1, (int) ((y - 48 - 8) / lineHeight)));
                        float rowHeight = count * lineHeight + 8;
                        if (rowIndex % 2 == 1) {
                            stream.setNonStrokingColor(246 / 255f, 249 / 255f, 252 / 255f);
                            stream.addRect(margin, y - rowHeight, available, rowHeight);
                            stream.fill();
                        }
                        float x = margin;
                        for (int c = 0; c < widths.length; c++) {
                            float red = 10 / 255f, green = 47 / 255f, blue = 86 / 255f;
                            if (c == row.accentColumn()) {
                                if (row.sign() < 0) { red = .8f; green = .16f; blue = .16f; }
                                else if (row.sign() > 0) { red = .55f; green = .4f; blue = 0; }
                                else { red = .1f; green = .45f; blue = .2f; }
                            }
                            for (int line = offset; line < Math.min(cells.get(c).size(), offset + count); line++)
                                text(stream, fonts, false, fontSize, x + 8, y - 8 - (line - offset + 1) * lineHeight + fontSize,
                                        cells.get(c).get(line), red, green, blue);
                            x += widths[c];
                        }
                        stream.setStrokingColor(.87f, .9f, .93f);
                        stream.moveTo(margin, y - rowHeight);
                        stream.lineTo(margin + available, y - rowHeight);
                        stream.stroke();
                        y -= rowHeight;
                        offset += count;
                    }
                    rowIndex++;
                }
            } finally { if (stream != null) stream.close(); }
            int pageCount = doc.getNumberOfPages();
            for (int index = 0; index < pageCount; index++) {
                try (var footer = new PDPageContentStream(doc, doc.getPage(index), AppendMode.APPEND, true, true)) {
                    String label = tr(spec.locale(), "Página", "Page", "第") + " " + (index + 1)
                            + tr(spec.locale(), " de ", " of ", " / ") + pageCount;
                    text(footer, fonts, false, 8, margin, 22, label, 10 / 255f, 47 / 255f, 86 / 255f);
                }
            }
            doc.save(output);
            return output.toByteArray();
        }
    }

    private record PdfFonts(PDFont regular, PDFont bold, PDFont fallback) {
        PDFont forText(String value, boolean useBold) throws IOException {
            PDFont preferred = useBold ? bold : regular;
            try { preferred.getStringWidth(value); return preferred; }
            catch (IllegalArgumentException unsupported) { return fallback; }
        }
    }

    private static void title(PDPageContentStream stream, PdfFonts fonts, PDRectangle page,
            float margin, float available, String value) throws IOException {
        stream.setNonStrokingColor(PDF_NAVY[0], PDF_NAVY[1], PDF_NAVY[2]);
        stream.addRect(margin, page.getHeight() - 67, available, 40);
        stream.fill();
        text(stream, fonts, true, 15, margin + 8, page.getHeight() - 53, value, 1, 1, 1);
    }

    private static void text(PDPageContentStream stream, PdfFonts fonts, boolean bold,
            float size, float x, float y, String value, float r, float g, float b) throws IOException {
        PDFont chosen = fonts.forText(Objects.toString(value, ""), bold);
        String printable = safe(chosen, Objects.toString(value, ""));
        stream.setNonStrokingColor(r, g, b);
        if (bold && chosen == fonts.fallback()) stream.setStrokingColor(r, g, b);
        stream.beginText();
        stream.setFont(chosen, size);
        if (bold && chosen == fonts.fallback()) {
            stream.setRenderingMode(RenderingMode.FILL_STROKE);
            stream.setLineWidth(.25f);
        }
        stream.newLineAtOffset(x, y);
        stream.showText(printable);
        stream.endText();
        if (bold && chosen == fonts.fallback()) stream.setRenderingMode(RenderingMode.FILL);
    }

    private static String safe(PDFont font, String value) throws IOException {
        var result = new StringBuilder();
        for (int point : value.codePoints().toArray()) {
            if (Character.isISOControl(point)) { result.append(' '); continue; }
            String character = new String(Character.toChars(point));
            try { font.getStringWidth(character); result.append(character); }
            catch (IllegalArgumentException exception) { result.append('?'); }
        }
        return result.toString();
    }

    private static List<String> wrap(PdfFonts fonts, String value, float width, float fontSize, boolean bold)
            throws IOException {
        var result = new ArrayList<String>();
        for (String paragraph : Objects.toString(value, "").split("\\R", -1)) {
            var line = new StringBuilder();
            var tokens = java.util.regex.Pattern.compile("\\S+|\\s+").matcher(paragraph.replace('\t', ' '));
            while (tokens.find()) {
                String token = tokens.group();
                if (token.isBlank()) {
                    if (!line.isEmpty() && measure(fonts, line + token, fontSize, bold) <= width) line.append(token);
                    else if (!line.isEmpty()) { result.add(line.toString().stripTrailing()); line.setLength(0); }
                    continue;
                }
                if (!line.isEmpty() && measure(fonts, line + token, fontSize, bold) > width) {
                    result.add(line.toString().stripTrailing());
                    line.setLength(0);
                }
                if (measure(fonts, token, fontSize, bold) <= width) { line.append(token); continue; }
                for (int point : token.codePoints().toArray()) {
                    String character = new String(Character.toChars(point));
                    if (!line.isEmpty() && measure(fonts, line + character, fontSize, bold) > width) {
                        result.add(line.toString().stripTrailing());
                        line.setLength(0);
                    }
                    line.append(character);
                }
            }
            result.add(line.toString().stripTrailing());
        }
        return result;
    }

    private static float measure(PdfFonts fonts, String value, float size, boolean bold) throws IOException {
        PDFont chosen = fonts.forText(value, bold);
        return chosen.getStringWidth(safe(chosen, value)) * size / 1000;
    }

    private static PDFont font(PDDocument doc, boolean unicode) throws IOException {
        if (!unicode) return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (var input = WarehouseExportLayout.class.getResourceAsStream("/fonts/NotoSansSC-VF.ttf")) {
            if (input == null) throw new IOException("No se encontró la fuente Noto Sans SC del informe");
            return PDType0Font.load(doc, input, true);
        }
    }
}
