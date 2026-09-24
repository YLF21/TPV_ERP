package com.tpverp.backend.inventory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class StockCountExportService {
    private final StockCountService counts;

    public StockCountExportService(StockCountService counts) { this.counts = counts; }

    public byte[] excel(UUID id) {
        var count = counts.get(id);
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Inventario");
            var header = style(workbook, IndexedColors.GREY_25_PERCENT, true);
            var less = style(workbook, IndexedColors.ROSE, false);
            var equal = style(workbook, IndexedColors.LIGHT_GREEN, false);
            var more = style(workbook, IndexedColors.LIGHT_YELLOW, false);
            var title = sheet.createRow(0);
            title.createCell(0).setCellValue("INVENTARIO");
            sheet.createRow(1).createCell(0).setCellValue("Documento: " + count.number());
            sheet.createRow(2).createCell(0).setCellValue("Estado: " + count.status());
            var labels = List.of("Código", "Código de barras", "Producto", "Antes", "Después", "Diferencia");
            var heading = sheet.createRow(4);
            for (int column = 0; column < labels.size(); column++) {
                var cell = heading.createCell(column);
                cell.setCellValue(labels.get(column));
                cell.setCellStyle(header);
            }
            int rowNumber = 5;
            for (var line : count.lines()) {
                var row = sheet.createRow(rowNumber++);
                row.createCell(0).setCellValue(safe(line.productCode()));
                row.createCell(1).setCellValue(safe(line.productBarcode()));
                row.createCell(2).setCellValue(safe(line.productName()));
                row.createCell(3).setCellValue(line.expectedQuantity().doubleValue());
                var counted = row.createCell(4);
                if (line.countedQuantity() != null) counted.setCellValue(line.countedQuantity().doubleValue());
                var delta = row.createCell(5);
                if (line.difference() != null) {
                    delta.setCellValue(line.difference().doubleValue());
                    delta.setCellStyle(line.difference().signum() < 0 ? less : line.difference().signum() > 0 ? more : equal);
                }
            }
            for (int column = 0; column < labels.size(); column++) sheet.setColumnWidth(column, column == 2 ? 11000 : 5300);
            sheet.createFreezePane(0, 5);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) { throw new IllegalStateException("No se pudo exportar el inventario", exception); }
    }

    public byte[] pdf(UUID id) {
        var count = counts.get(id);
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            PDFont embedded = multilingualFont(document);
            PDFont normal = embedded == null ? new PDType1Font(Standard14Fonts.FontName.HELVETICA) : embedded;
            PDFont bold = embedded == null ? new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD) : embedded;
            PDPageContentStream content = null;
            float y = 0;
            try {
                for (int index = -1; index < count.lines().size(); index++) {
                    if (content == null || y < 45) {
                        if (content != null) content.close();
                        var page = new PDPage(new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()));
                        document.addPage(page);
                        content = new PDPageContentStream(document, page);
                        y = page.getMediaBox().getHeight() - 38;
                        drawText(content, bold, 15, 36, y, "INVENTARIO");
                        y -= 21;
                        drawText(content, normal, 8, 36, y, "Documento: " + count.number() + "   Estado: " + count.status());
                        y -= 25;
                        drawRow(content, bold, y, List.of("Código", "Barras", "Producto", "Antes", "Después", "Diferencia"), 0);
                        y -= 20;
                    }
                    if (index < 0) continue;
                    var line = count.lines().get(index);
                    drawRow(content, normal, y, List.of(safe(line.productCode()), safe(line.productBarcode()),
                            safe(line.productName()), number(line.expectedQuantity()), number(line.countedQuantity()),
                            number(line.difference())), line.difference() == null ? 0 : line.difference().signum());
                    y -= 20;
                }
            } finally { if (content != null) content.close(); }
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) { throw new IllegalStateException("No se pudo exportar el inventario", exception); }
    }

    private static CellStyle style(XSSFWorkbook book, IndexedColors fill, boolean bold) {
        var style = book.createCellStyle();
        style.setFillForegroundColor(fill.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        var font = book.createFont();
        font.setBold(bold);
        style.setFont(font);
        return style;
    }

    private static void drawRow(PDPageContentStream content, PDFont font, float y, List<String> values, int sign)
            throws IOException {
        float[] widths = {100, 110, 235, 75, 75, 75};
        float x = 36;
        for (int i = 0; i < values.size(); i++) {
            if (i == 5) {
                if (sign < 0) content.setNonStrokingColor(.8f, .16f, .16f);
                else if (sign > 0) content.setNonStrokingColor(.55f, .4f, 0f);
                else content.setNonStrokingColor(.1f, .45f, .2f);
            } else content.setNonStrokingColor(0f, 0f, 0f);
            drawText(content, font, 8, x + 2, y, fit(values.get(i), i == 2 ? 42 : 18));
            x += widths[i];
        }
        content.setStrokingColor(.75f, .75f, .75f);
        content.moveTo(36, y - 5);
        content.lineTo(x, y - 5);
        content.stroke();
    }

    private static void drawText(PDPageContentStream content, PDFont font, int size, float x, float y, String value)
            throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(printable(font, value));
        content.endText();
    }

    private static String fit(String value, int limit) { return value.length() <= limit ? value : value.substring(0, limit - 1) + "…"; }
    private static String printable(PDFont font, String value) throws IOException {
        if (font instanceof PDType1Font) return value.replaceAll("[^\\x20-\\x7E\\xA1-\\xFF]", "?");
        try { font.getStringWidth(value); return value; }
        catch (IllegalArgumentException exception) {
            var result = new StringBuilder();
            value.codePoints().forEach(point -> {
                var character = new String(Character.toChars(point));
                try { font.getStringWidth(character); result.append(character); }
                catch (IOException | IllegalArgumentException ignored) { result.append('?'); }
            });
            return result.toString();
        }
    }
    private static PDFont multilingualFont(PDDocument document) {
        var windows = System.getenv("WINDIR");
        var candidates = List.of(
                Path.of(windows == null ? "C:/Windows" : windows, "Fonts", "NotoSansSC-VF.ttf"),
                Path.of("/usr/share/fonts/truetype/noto/NotoSansSC-Regular.ttf"));
        for (var candidate : candidates) {
            if (!Files.isRegularFile(candidate)) continue;
            try (var input = Files.newInputStream(candidate)) {
                return PDType0Font.load(document, input, true);
            } catch (IOException ignored) { /* The standard PDF font remains available. */ }
        }
        return null;
    }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String number(BigDecimal value) { return value == null ? "" : value.stripTrailingZeros().toPlainString(); }
}
