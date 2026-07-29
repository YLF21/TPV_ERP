package com.tpverp.backend.excel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.text.Normalizer;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class SalesReportPdfExportService {

    private static final Logger LOG = LoggerFactory.getLogger(SalesReportPdfExportService.class);
    private static final float MARGIN = 34;
    private static final float ROW_HEIGHT = 18;
    private static final DateTimeFormatter GENERATED_AT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private final SalesReportExcelExportService excel;

    public SalesReportPdfExportService(SalesReportExcelExportService excel) {
        this.excel = excel;
    }

    public byte[] export(SalesReportExportRequest request, Authentication authentication) {
        byte[] workbookBytes = excel.export(request, authentication);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(workbookBytes));
             var document = new PDDocument();
             var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheetAt(0);
            var locale = Locale.forLanguageTag("es-ES");
            var formatter = new DataFormatter(locale);
            var currencyFormatter = NumberFormat.getNumberInstance(locale);
            currencyFormatter.setMinimumFractionDigits(2);
            currencyFormatter.setMaximumFractionDigits(2);
            var rows = new ArrayList<List<String>>();
            sheet.forEach(row -> {
                var values = new ArrayList<String>();
                for (int column = 0; column < request.columns().size(); column++) {
                    values.add(formatCell(row.getCell(column), formatter, currencyFormatter));
                }
                rows.add(values);
            });
            render(document, request.reportKey(), rows);
            document.save(output);
            return output.toByteArray();
        } catch (IOException | RuntimeException exception) {
            LOG.error("Could not generate sales report PDF for {}", request.reportKey(), exception);
            throw new IllegalStateException("No se pudo generar el PDF del informe", exception);
        }
    }

    private String formatCell(Cell cell, DataFormatter formatter, NumberFormat currencyFormatter) {
        if (cell == null) return "";
        String dataFormat = cell.getCellStyle().getDataFormatString();
        if (cell.getCellType() == CellType.NUMERIC
                && dataFormat != null
                && dataFormat.contains("\u20ac")) {
            return currencyFormatter.format(cell.getNumericCellValue()) + " \u20ac";
        }
        return formatter.formatCellValue(cell);
    }

    private void render(PDDocument document, String reportKey, List<List<String>> rows) throws IOException {
        var regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        var bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        PDPage page = null;
        PDPageContentStream content = null;
        float y = 0;
        float pageWidth = PDRectangle.A4.getHeight();
        float columnWidth = (pageWidth - MARGIN * 2) / Math.max(1, rows.isEmpty() ? 1 : rows.getFirst().size());

        try {
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                if (page == null || y < MARGIN + ROW_HEIGHT) {
                    if (content != null) content.close();
                    page = new PDPage(new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()));
                    document.addPage(page);
                    content = new PDPageContentStream(document, page);
                    y = page.getMediaBox().getHeight() - MARGIN;
                    text(content, bold, 15, MARGIN, y, readableReportName(reportKey));
                    text(content, regular, 8, pageWidth - 160, y, "Generado: " + GENERATED_AT.format(LocalDateTime.now()));
                    y -= 28;
                    if (rowIndex > 0 && !rows.isEmpty()) {
                        drawRow(content, rows.getFirst(), bold, columnWidth, y, true);
                        y -= ROW_HEIGHT;
                    }
                }
                drawRow(content, rows.get(rowIndex), rowIndex == 0 ? bold : regular, columnWidth, y, rowIndex == 0);
                y -= ROW_HEIGHT;
            }
        } finally {
            if (content != null) content.close();
        }
    }

    private void drawRow(
            PDPageContentStream content,
            List<String> values,
            PDType1Font font,
            float columnWidth,
            float y,
            boolean header) throws IOException {
        if (header) {
            content.setNonStrokingColor(10 / 255f, 47 / 255f, 86 / 255f);
            content.addRect(MARGIN, y - 4, columnWidth * values.size(), ROW_HEIGHT);
            content.fill();
        }
            content.setStrokingColor(200 / 255f, 213 / 255f, 225 / 255f);
        for (int index = 0; index < values.size(); index++) {
            float x = MARGIN + index * columnWidth;
            content.addRect(x, y - 4, columnWidth, ROW_HEIGHT);
            content.stroke();
            content.setNonStrokingColor(
                    header ? 1f : 20 / 255f,
                    header ? 1f : 43 / 255f,
                    header ? 1f : 66 / 255f);
            text(content, font, 7, x + 4, y + 2, fit(values.get(index), font, 7, columnWidth - 8));
        }
    }

    private void text(
            PDPageContentStream content,
            PDType1Font font,
            float size,
            float x,
            float y,
            String value) throws IOException {
        String safe = sanitize(value);
        boolean hasEuro = safe.contains("€");
        String printable = safe.replace("€", "").trim();
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(printable);
        content.endText();
        if (hasEuro) {
            float euroX = x + font.getStringWidth(printable) / 1000 * size + 2;
            drawEuro(content, euroX, y, size);
        }
    }

    private String fit(String value, PDType1Font font, float size, float width) throws IOException {
        String safe = sanitize(value);
        if (textWidth(safe, font, size) <= width) return safe;
        while (safe.length() > 1 && textWidth(safe + "...", font, size) > width) {
            safe = safe.substring(0, safe.length() - 1);
        }
        return safe + "...";
    }

    private float textWidth(String value, PDType1Font font, float size) throws IOException {
        String printable = value.replace("€", "");
        return font.getStringWidth(printable) / 1000 * size + (value.contains("€") ? size * .75f : 0);
    }

    private void drawEuro(PDPageContentStream content, float x, float y, float size) throws IOException {
        float width = size * .55f;
        float height = size * .75f;
        content.setStrokingColor(20 / 255f, 43 / 255f, 66 / 255f);
        content.setLineWidth(Math.max(.45f, size / 12));
        content.moveTo(x + width, y + height);
        content.curveTo(x, y + height, x, y - height * .15f, x + width, y);
        content.moveTo(x, y + height * .48f);
        content.lineTo(x + width * .78f, y + height * .48f);
        content.moveTo(x, y + height * .28f);
        content.lineTo(x + width * .72f, y + height * .28f);
        content.stroke();
    }

    private String sanitize(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(
                value.replace("\n", " ").replace("\r", " "),
                Normalizer.Form.NFD
        ).replaceAll("\\p{M}", "");
        return normalized.replaceAll("[^\\x20-\\x7E€]", "?");
    }

    private String readableReportName(String reportKey) {
        return switch (reportKey) {
            case "salesReport.dailySales" -> "Ventas diarias";
            case "salesReport.tickets" -> "Tickets";
            case "salesReport.deliveryNotes" -> "Albaranes";
            case "salesReport.invoices" -> "Facturas";
            case "salesReport.warehouseOutputs" -> "Salidas de almacen";
            case "salesReport.inputDeliveryNotes" -> "Entradas de albaran";
            case "salesReport.inputInvoices" -> "Entradas de factura";
            case "salesReport.inputWarehouse" -> "Entradas de almacen";
            default -> "Informe";
        };
    }
}
