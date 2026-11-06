package com.tpverp.backend.inventory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class ReplenishmentExportService {
    private static final int MAX_ROWS = 50_000;
    private static final float[] WIDTHS = {86, 65, 84, 129, 52, 119, 54, 63, 84, 74, 76, 68, 73};
    private static final float LEFT = 26;
    private static final float FONT_SIZE = 8;
    private static final float LINE_HEIGHT = 11;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public byte[] export(String format, String locale, List<ReplenishmentView> items) {
        if (!Set.of("pdf", "xlsx").contains(format)) throw new IllegalArgumentException("Formato no válido");
        Objects.requireNonNull(items, "items");
        if (items.size() > MAX_ROWS) throw new IllegalArgumentException("El informe supera 50000 solicitudes");
        var copy = new Copy(locale);
        try {
            return format.equals("xlsx") ? excel(copy, items) : pdf(copy, items);
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo exportar el listado de reposición", exception);
        }
    }

    private byte[] excel(Copy copy, List<ReplenishmentView> items) throws IOException {
        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = book.createSheet(copy.title());
            var header = book.createCellStyle();
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var bold = book.createFont(); bold.setBold(true); header.setFont(bold);
            var numeric = book.createCellStyle(); numeric.setDataFormat(book.createDataFormat().getFormat("0.###"));
            var heading = sheet.createRow(0);
            for (int c = 0; c < WIDTHS.length; c++) {
                var cell = heading.createCell(c); cell.setCellValue(copy.headers().get(c)); cell.setCellStyle(header);
                sheet.setColumnWidth(c, Math.min(255 * 256, Math.round(WIDTHS[c] * 42)));
            }
            for (int r = 0; r < items.size(); r++) {
                var row = sheet.createRow(r + 1);
                var values = values(copy, items.get(r));
                for (int c = 0; c < values.size(); c++) {
                    var cell = row.createCell(c);
                    if (numeric(c) && !values.get(c).isEmpty()) {
                        cell.setCellValue(new BigDecimal(values.get(c)).doubleValue()); cell.setCellStyle(numeric);
                    } else {
                        // POI stores all untrusted text (including =, +, - and @ prefixes) as literal strings.
                        cell.setCellType(CellType.STRING); cell.setCellValue(values.get(c));
                    }
                }
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, items.size(), 0, WIDTHS.length - 1));
            book.write(output); return output.toByteArray();
        }
    }

    private byte[] pdf(Copy copy, List<ReplenishmentView> items) throws IOException {
        try (var doc = new PDDocument(); var output = new ByteArrayOutputStream()) {
            boolean multilingual = copy.zh() || items.stream().flatMap(item -> values(copy, item).stream())
                    .anyMatch(value -> value.codePoints().anyMatch(p -> p > 255));
            var font = font(doc, multilingual);
            var numbers = java.text.NumberFormat.getNumberInstance(copy.numberLocale());
            numbers.setMaximumFractionDigits(3);
            PDPageContentStream content = null;
            float y = 0;
            try {
                for (int r = 0; r < Math.max(1, items.size()); r++) {
                    var values = items.isEmpty() ? java.util.Collections.nCopies(WIDTHS.length, "") : values(copy, items.get(r));
                    var cells = new ArrayList<List<String>>();
                    for (int c = 0; c < WIDTHS.length; c++) {
                        String value = numeric(c) && !values.get(c).isEmpty()
                                ? numbers.format(new BigDecimal(values.get(c))) : values.get(c);
                        cells.add(wrap(font, value, WIDTHS[c] - 8));
                    }
                    int lines = cells.stream().mapToInt(List::size).max().orElse(1);
                    for (int offset = 0; offset < lines;) {
                        if (content == null || y < 54) {
                            if (content != null) content.close();
                            var page = new PDPage(new PDRectangle(PDRectangle.A3.getHeight(), PDRectangle.A3.getWidth()));
                            doc.addPage(page); content = new PDPageContentStream(doc, page);
                            text(content, font, 15, LEFT, page.getMediaBox().getHeight() - 31, copy.title());
                            text(content, font, 8, page.getMediaBox().getWidth() - 48,
                                    page.getMediaBox().getHeight() - 31, Integer.toString(doc.getNumberOfPages()));
                            y = page.getMediaBox().getHeight() - 71;
                            content.setNonStrokingColor(.9f, .9f, .9f);
                            content.addRect(LEFT, y - 10, totalWidth(), 28); content.fill();
                            for (int c = 0; c < WIDTHS.length; c++) {
                                var labels = wrap(font, copy.headers().get(c), WIDTHS[c] - 8);
                                for (int line = 0; line < Math.min(2, labels.size()); line++)
                                    text(content, font, FONT_SIZE, x(c) + 4, y + 7 - line * LINE_HEIGHT, labels.get(line));
                            }
                            y -= 13;
                        }
                        int count = Math.min(lines - offset, Math.max(1, (int) ((y - 34) / LINE_HEIGHT) - 1));
                        float height = count * LINE_HEIGHT + 8;
                        content.setStrokingColor(.75f, .75f, .75f);
                        for (int c = 0; c < WIDTHS.length; c++) {
                            content.addRect(x(c), y - height, WIDTHS[c], height); content.stroke();
                            for (int line = offset; line < Math.min(cells.get(c).size(), offset + count); line++)
                                text(content, font, FONT_SIZE, x(c) + 4, y - 11 - (line - offset) * LINE_HEIGHT, cells.get(c).get(line));
                        }
                        y -= height; offset += count;
                    }
                }
            } finally { if (content != null) content.close(); }
            doc.save(output); return output.toByteArray();
        }
    }

    private static boolean numeric(int column) { return column == 4 || column == 6 || column == 7; }
    private static float totalWidth() { float width = 0; for (float value : WIDTHS) width += value; return width; }
    private static float x(int column) { float value = LEFT; for (int c = 0; c < column; c++) value += WIDTHS[c]; return value; }
    private static String value(Object object) { return Objects.toString(object, ""); }
    private static String decimal(BigDecimal number) { return number == null ? "" : number.toPlainString(); }
    private static List<String> values(Copy copy, ReplenishmentView item) {
        return List.of(value(item.warehouseName()), value(item.code()), value(item.barcode()), value(item.name()),
                decimal(item.quantity()), value(item.reason()), decimal(item.stock()), decimal(item.purchasePrice()),
                value(item.supplierName()), value(item.familyName()), value(item.subfamilyName()),
                copy.status(item.status()), item.orderDate() == null ? "" : DATE.format(item.orderDate()));
    }

    private static void text(PDPageContentStream content, PDFont font, float size, float x, float y, String value) throws IOException {
        content.setNonStrokingColor(0f, 0f, 0f); content.beginText(); content.setFont(font, size);
        content.newLineAtOffset(x, y); content.showText(safe(font, value)); content.endText();
    }
    private static String safe(PDFont font, String value) throws IOException {
        var result = new StringBuilder();
        for (int point : value.replaceAll("[\\p{Cntrl}]", " ").codePoints().toArray()) {
            String character = new String(Character.toChars(point));
            try { font.getStringWidth(character); result.append(character); }
            catch (IllegalArgumentException exception) { result.append('?'); }
        }
        return result.toString();
    }
    private static List<String> wrap(PDFont font, String value, float width) throws IOException {
        var lines = new ArrayList<String>(); var line = new StringBuilder();
        for (int point : safe(font, value).codePoints().toArray()) {
            String character = new String(Character.toChars(point));
            if (!line.isEmpty() && font.getStringWidth(line + character) * FONT_SIZE / 1000 > width) {
                int space = line.lastIndexOf(" ");
                if (space > 0) { lines.add(line.substring(0, space)); line.delete(0, space + 1); }
                else { lines.add(line.toString()); line.setLength(0); }
            }
            line.append(character);
        }
        lines.add(line.toString()); return lines;
    }
    private static PDFont font(PDDocument doc, boolean multilingual) throws IOException {
        if (!multilingual) return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (var input = ReplenishmentExportService.class.getResourceAsStream("/fonts/NotoSansSC-VF.ttf")) {
            if (input == null) throw new IOException("No se encontró la fuente Noto Sans SC del informe");
            return PDType0Font.load(doc, input, true);
        }
    }
    private record Copy(String locale) {
        boolean zh() { return locale != null && locale.startsWith("zh"); }
        Locale numberLocale() { return Locale.forLanguageTag(locale == null ? "es" : locale); }
        String choose(String es, String en, String zh) { return zh() ? zh : locale != null && locale.startsWith("en") ? en : es; }
        String title() { return choose("Solicitudes de reposición", "Replenishment requests", "补货申请"); }
        List<String> headers() { return List.of(choose("Almacén", "Warehouse", "仓库"), choose("Código", "Code", "编码"),
                choose("Código de barras", "Barcode", "条码"), choose("Producto", "Product", "商品"),
                choose("Cantidad", "Quantity", "数量"), choose("Motivo", "Reason", "原因"),
                choose("Stock", "Stock", "库存"), choose("Precio compra", "Purchase price", "进价"),
                choose("Proveedor", "Supplier", "供应商"), choose("Familia", "Family", "类别"),
                choose("Subfamilia", "Subfamily", "子类别"), choose("Estado", "Status", "状态"),
                choose("Fecha pedido", "Order date", "订货日期")); }
        String status(String status) { return switch (value(status)) {
            case "PENDING" -> choose("Pendiente", "Pending", "待处理");
            case "ORDERED" -> choose("Pedido", "Ordered", "已订货");
            default -> value(status);
        }; }
    }
}
