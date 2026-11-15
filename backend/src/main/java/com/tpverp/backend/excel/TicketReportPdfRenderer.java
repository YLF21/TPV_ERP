package com.tpverp.backend.excel;

import java.io.IOException;
import java.io.InputStream;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

/** A4 sales document report built from the exported workbook, without changing its amounts. */
final class TicketReportPdfRenderer {
    private static final float MARGIN = 20;
    private static final float PADDING = 8;
    private static final float BOTTOM = 51;
    private static final float BODY_FONT = 8.5f;
    private static final float CELL_LINE = 11;
    private static final Pattern NUMBER_TOKEN = Pattern.compile("-?[0-9][0-9.,:/-]*");
    private static final Set<String> MONEY = Set.of(
            "total", "subtotal", "pending", "base", "tax", "discount", "memberBalance");
    private static final String FONT_RESOURCE = "/fonts/NotoSansSC-VF.ttf";
    private static final float[] NAVY = {10 / 255f, 47 / 255f, 86 / 255f};

    private TicketReportPdfRenderer() {
    }

    static void render(PDDocument document, Sheet sheet, SalesReportExportRequest request) throws IOException {
        try (InputStream fontStream = TicketReportPdfRenderer.class.getResourceAsStream(FONT_RESOURCE)) {
            if (fontStream == null) throw new IOException("Sales document PDF font missing: " + FONT_RESOURCE);
            PDFont font = PDType0Font.load(document, fontStream, true);
            DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("es-ES"));
            NumberFormat money = NumberFormat.getNumberInstance(Locale.forLanguageTag("es-ES"));
            money.setMinimumFractionDigits(2);
            money.setMaximumFractionDigits(2);
            Map<String, String> metadata = metadata(sheet, formatter);
            String title = cell(sheet.getRow(0), 0, formatter, money);
            List<String> headings = values(sheet.getRow(8), request, formatter, money);
            List<List<String>> data = new ArrayList<>();
            List<String> total = null;
            for (int index = 9; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null) continue;
                List<String> values = values(row, request, formatter, money);
                if (index == sheet.getLastRowNum() && isTotal(row, formatter)) total = values;
                else data.add(values);
            }
            var painter = new Painter(document, font, PDRectangle.A4, title, metadata,
                    request.columns(), headings);
            painter.selectPageSize(data, total, "salesReport.inputInvoices".equals(request.reportKey())
                    || "salesReport.inputDeliveryNotes".equals(request.reportKey())
                    || "salesReport.inputWarehouse".equals(request.reportKey())
                    || "salesReport.warehouseOutputs".equals(request.reportKey()));
            painter.newPage();
            if (data.isEmpty()) painter.emptyRow();
            else for (int i = 0; i < data.size(); i++) painter.dataRow(data.get(i), i);
            if (total != null) painter.totalRow(total);
            painter.close();
            painter.footers();
        }
    }

    private static boolean isTotal(Row row, DataFormatter formatter) {
        for (Cell cell : row) {
            if ("TOTAL".equalsIgnoreCase(formatter.formatCellValue(cell).trim())) return true;
        }
        return false;
    }

    private static Map<String, String> metadata(Sheet sheet, DataFormatter formatter) {
        Map<String, String> result = new HashMap<>();
        for (int i = 1; i <= 6; i++) {
            Row row = sheet.getRow(i);
            String label = cell(row, 0, formatter, null);
            String value = cell(row, 1, formatter, null);
            if (value.isBlank()) {
                int colon = label.indexOf(':');
                if (colon >= 0) {
                    value = label.substring(colon + 1).trim();
                    label = label.substring(0, colon).trim();
                }
            }
            result.put(label.toLowerCase(Locale.ROOT), value);
        }
        return result;
    }

    private static List<String> values(Row row, SalesReportExportRequest request,
            DataFormatter formatter, NumberFormat money) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < request.columns().size(); i++) {
            result.add(cell(row, i, formatter,
                    MONEY.contains(request.columns().get(i).key()) ? money : null));
        }
        return result;
    }

    private static String cell(Row row, int index, DataFormatter formatter, NumberFormat money) {
        if (row == null || row.getCell(index) == null) return "";
        Cell cell = row.getCell(index);
        if (money != null && cell.getCellType() == CellType.NUMERIC) {
            return money.format(cell.getNumericCellValue()) + " €";
        }
        return formatter.formatCellValue(cell);
    }

    private static final class Painter {
        private final PDDocument document;
        private final PDFont fallback;
        private final PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private final PDFont boldFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        private PDRectangle size;
        private final String title;
        private final Map<String, String> metadata;
        private PDPageContentStream stream;
        private float y;
        private float bodyTop;
        private float[] columnWidths;
        private float headerHeight;
        private List<SalesReportExportRequest.Column> selected;
        private List<String> headings;

        private Painter(PDDocument document, PDFont font, PDRectangle size,
                String title, Map<String, String> metadata,
                List<SalesReportExportRequest.Column> selected, List<String> headings) {
            this.document = document;
            this.fallback = font;
            this.size = size;
            this.title = title.isBlank() ? "Informe de ventas" : title;
            this.metadata = metadata;
            this.selected = selected;
            this.headings = headings;
        }

        private void newPage() throws IOException {
            if (stream != null) stream.close();
            boolean firstPage = document.getNumberOfPages() == 0;
            PDPage page = new PDPage(size);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            float top = size.getHeight() - MARGIN;
            if (!firstPage) {
                y = top;
                drawTableHeader();
                bodyTop = y;
                return;
            }
            fill(NAVY, MARGIN, top - 42, size.getWidth() - 2 * MARGIN, 42);
            whiteText(16, MARGIN + 14, top - 27, title, true);
            List<String> left = new ArrayList<>();
            float leftX = MARGIN + 14;
            float rightWidth = 198;
            float rightX = size.getWidth() - MARGIN - 14 - rightWidth;
            float leftWidth = rightX - leftX - 16;
            for (String detail : List.of("EMPRESA  " + meta("empresa"), "NIF  " + meta("nif"),
                    "TIENDA  " + meta("tienda"), "DIRECCIÓN  " + meta("dirección"))) {
                left.addAll(wrap(detail, leftWidth, 8));
            }
            List<String> right = new ArrayList<>();
            for (String detail : List.of("PERIODO  " + meta("periodo"),
                    "EMITIDO  " + meta("emitido"))) {
                right.addAll(wrap(detail, rightWidth, 8));
            }
            float leftY = top - 58;
            for (String line : left) { darkText(8, leftX, leftY, line); leftY -= 12; }
            float rightY = top - 58;
            for (String line : right) { darkText(8, rightX, rightY, line); rightY -= 12; }
            y = Math.min(leftY, rightY) - 3;
            y -= 8;
            drawTableHeader();
            bodyTop = y;
        }

        private String meta(String key) {
            if ("dirección".equals(key)) return metadata.getOrDefault(key,
                    metadata.getOrDefault("direccion", ""));
            return metadata.getOrDefault(key, "");
        }

        private void selectPageSize(List<List<String>> data, List<String> total, boolean purchaseReport) throws IOException {
            int count = selected.size();
            float[] wanted = new float[count];
            float[] minimum = new float[count];
            float sum = 0;
            float minimumSum = 0;
            int labelColumn = -1;
            for (int i = 0; i < count; i++) {
                if (!MONEY.contains(selected.get(i).key())) { labelColumn = i; break; }
            }
            for (int i = 0; i < count; i++) {
                String key = selected.get(i).key();
                float cap = switch (key) {
                    case "date" -> 88;
                    case "time" -> 66;
                    case "ticket", "invoice", "deliveryNote", "input", "output" -> 115;
                    case "terminal" -> 100;
                    case "customer" -> 150;
                    case "comment", "description", "detail" -> 200;
                    default -> MONEY.contains(key) ? 110 : 135;
                };
                float natural = width(label(i), 9, true);
                float floor = 42;
                if (purchaseReport) floor = Math.max(floor, Math.min(cap, wordFloor(label(i), 9, true)));
                for (List<String> row : data) {
                    for (String paragraph : normalized(row.get(i)).split("\\n", -1)) {
                        natural = Math.max(natural, width(paragraph, BODY_FONT));
                        if ("date".equals(key) || "time".equals(key))
                            floor = Math.max(floor, width(paragraph, BODY_FONT) + 2 * PADDING);
                        if (purchaseReport && Set.of("invoice", "deliveryNote", "input", "output", "supplier", "warehouse", "status",
                                "productCount", "globalDiscount").contains(key))
                            floor = Math.max(floor, Math.min(cap, wordFloor(paragraph, BODY_FONT, false)));
                        floor = Math.max(floor, numberFloor(paragraph, BODY_FONT, false));
                    }
                }
                if (total != null) {
                    natural = Math.max(natural, width(total.get(i), 14, true));
                    floor = Math.max(floor, numberFloor(total.get(i), 14, true));
                }
                if (total != null && i == labelColumn) {
                    floor = Math.max(floor, width("TOTAL", 14, true) + 2 * PADDING);
                }
                minimum[i] = floor;
                minimumSum += floor;
                wanted[i] = Math.max(floor, Math.min(cap, natural + 2 * PADDING));
                sum += wanted[i];
            }
            float portrait = PDRectangle.A4.getWidth() - 2 * MARGIN;
            if (sum > portrait) size = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
            float available = size.getWidth() - 2 * MARGIN;
            columnWidths = new float[count];
            if (count == 0) return;
            if (sum <= available) {
                float textWeight = 0;
                for (int i = 0; i < count; i++) {
                    if (!MONEY.contains(selected.get(i).key())
                            && !"date".equals(selected.get(i).key())
                            && !"time".equals(selected.get(i).key())) textWeight += wanted[i];
                }
                for (int i = 0; i < count; i++) {
                    boolean textColumn = !MONEY.contains(selected.get(i).key())
                            && !"date".equals(selected.get(i).key())
                            && !"time".equals(selected.get(i).key());
                    columnWidths[i] = wanted[i] + (available - sum)
                            * (textWeight > 0 ? (textColumn ? wanted[i] / textWeight : 0) : 1f / count);
                }
            } else {
                if (minimumSum > available) {
                    for (int i = 0; i < count; i++) columnWidths[i] = minimum[i] * available / minimumSum;
                    return;
                }
                float textRoom = 0;
                for (int i = 0; i < count; i++) {
                    String key = selected.get(i).key();
                    if (!MONEY.contains(key) && !"date".equals(key) && !"time".equals(key))
                        textRoom += wanted[i] - minimum[i];
                }
                float textReduction = Math.min(sum - available, textRoom);
                for (int i = 0; i < count; i++) {
                    String key = selected.get(i).key();
                    boolean textColumn = !MONEY.contains(key)
                            && !"date".equals(key) && !"time".equals(key);
                    columnWidths[i] = wanted[i] - (textColumn && textRoom > 0
                            ? textReduction * (wanted[i] - minimum[i]) / textRoom : 0);
                }
                float remainder = sum - available - textReduction;
                float otherRoom = 0;
                for (int i = 0; i < count; i++) otherRoom += columnWidths[i] - minimum[i];
                if (remainder > 0 && otherRoom > 0) {
                    for (int i = 0; i < count; i++)
                        columnWidths[i] -= remainder * (columnWidths[i] - minimum[i]) / otherRoom;
                }
            }
        }

        private float wordFloor(String value, float fontSize, boolean bold) throws IOException {
            float floor = 0;
            for (String word : normalized(value).split("\\s+"))
                floor = Math.max(floor, width(word, fontSize, bold) + 2 * PADDING);
            return floor;
        }

        private float numberFloor(String value, float fontSize, boolean bold) throws IOException {
            float floor = 0;
            Matcher matcher = NUMBER_TOKEN.matcher(normalized(value));
            while (matcher.find()) {
                floor = Math.max(floor, width(matcher.group(), fontSize, bold) + 2 * PADDING);
            }
            return floor;
        }

        private void drawTableHeader() throws IOException {
            List<List<String>> lines = new ArrayList<>();
            int maxLines = 1;
            for (int i = 0; i < selected.size(); i++) {
                List<String> cell = wrap(label(i), columnWidths[i] - 2 * PADDING, 9, true);
                lines.add(cell);
                maxLines = Math.max(maxLines, cell.size());
            }
            headerHeight = 2 * PADDING + maxLines * 12;
            fill(NAVY, MARGIN, y - headerHeight, size.getWidth() - 2 * MARGIN, headerHeight);
            float x = MARGIN;
            for (int i = 0; i < selected.size(); i++) {
                float baseline = y - PADDING - 9;
                for (String line : lines.get(i)) {
                    if (MONEY.contains(selected.get(i).key()))
                        rightText(9, x + columnWidths[i] - PADDING, baseline, line, true, true);
                    else whiteText(9, x + PADDING, baseline, line, true);
                    baseline -= 12;
                }
                x += columnWidths[i];
            }
            y -= headerHeight;
        }

        private void emptyRow() throws IOException {
            if (y - 28 < BOTTOM) newPage();
            fill(new float[]{0.95f, 0.97f, 0.99f}, MARGIN, y - 28,
                    size.getWidth() - MARGIN * 2, 28);
            darkText(8, MARGIN + PADDING, y - 18, "Sin datos");
            y -= 28;
        }

        private void dataRow(List<String> values, int index) throws IOException {
            drawRow(values, index, false, false);
        }

        private void totalRow(List<String> source) throws IOException {
            List<String> values = new ArrayList<>(source);
            int labelColumn = -1;
            for (int i = 0; i < selected.size(); i++) {
                if (!MONEY.contains(selected.get(i).key())) { labelColumn = i; break; }
            }
            if (labelColumn < 0) {
                float summaryHeight = rowHeight(values, true);
                float pageCapacity = size.getHeight() - MARGIN - headerHeight - BOTTOM;
                float firstChunk = Math.min(summaryHeight, pageCapacity - 24);
                if (y - 24 - firstChunk < BOTTOM) newPage();
                fill(new float[]{0.90f, 0.94f, 0.97f}, MARGIN, y - 24,
                        size.getWidth() - 2 * MARGIN, 24);
                darkText(14, MARGIN + PADDING, y - 17, "TOTAL", true);
                y -= 24;
            } else {
                values.set(labelColumn, "TOTAL");
            }
            drawRow(values, 0, true, labelColumn < 0);
        }

        private float rowHeight(List<String> values, boolean summary) throws IOException {
            float fontSize = summary ? 14 : BODY_FONT;
            float lineHeight = summary ? 17 : CELL_LINE;
            int maxLines = 1;
            for (int i = 0; i < selected.size(); i++) {
                maxLines = Math.max(maxLines, wrap(values.get(i), columnWidths[i] - 2 * PADDING,
                        fontSize, summary).size());
            }
            return 2 * PADDING + maxLines * lineHeight;
        }

        private void drawRow(List<String> values, int index, boolean summary,
                boolean precededByBand) throws IOException {
            float fontSize = summary ? 14 : BODY_FONT;
            float lineHeight = summary ? 17 : CELL_LINE;
            List<List<String>> cells = new ArrayList<>();
            int maxLines = 1;
            for (int i = 0; i < selected.size(); i++) {
                List<String> lines = wrap(values.get(i), columnWidths[i] - 2 * PADDING,
                        fontSize, summary);
                cells.add(lines);
                maxLines = Math.max(maxLines, lines.size());
            }
            float fullHeight = 2 * PADDING + maxLines * lineHeight;
            float pageCapacity = size.getHeight() - MARGIN - headerHeight - BOTTOM;
            if (!precededByBand && fullHeight <= pageCapacity && y - fullHeight < BOTTOM) newPage();
            int offset = 0;
            while (offset < maxLines) {
                int fit = (int) Math.floor((y - BOTTOM - 2 * PADDING) / lineHeight);
                if (fit < 1) { newPage(); continue; }
                int count = Math.min(fit, maxLines - offset);
                float height = 2 * PADDING + count * lineHeight;
                fill(summary ? new float[]{0.90f, 0.94f, 0.97f}
                        : index % 2 == 0 ? new float[]{1f, 1f, 1f}
                        : new float[]{0.96f, 0.98f, 0.99f},
                        MARGIN, y - height, size.getWidth() - 2 * MARGIN, height);
                float x = MARGIN;
                for (int i = 0; i < selected.size(); i++) {
                    float baseline = y - PADDING - fontSize;
                    List<String> lines = cells.get(i);
                    for (int line = offset; line < Math.min(offset + count, lines.size()); line++) {
                        String value = lines.get(line);
                        if (MONEY.contains(selected.get(i).key()))
                            rightText(fontSize, x + columnWidths[i] - PADDING,
                                    baseline, value, false, summary);
                        else darkText(fontSize, x + PADDING, baseline, value, summary);
                        baseline -= lineHeight;
                    }
                    x += columnWidths[i];
                }
                stream.setStrokingColor(0.84f);
                stream.setLineWidth(0.35f);
                x = MARGIN;
                for (int i = 0; i <= selected.size(); i++) {
                    stream.moveTo(x, y);
                    stream.lineTo(x, y - height);
                    x += i < selected.size() ? columnWidths[i] : 0;
                }
                stream.moveTo(MARGIN, y - height);
                stream.lineTo(size.getWidth() - MARGIN, y - height);
                stream.stroke();
                y -= height;
                offset += count;
                if (offset < maxLines) newPage();
            }
        }

        private String label(int index) {
            return headings.get(index).isBlank() ? selected.get(index).label() : headings.get(index);
        }

        private List<String> wrap(String value, float width, float size) throws IOException {
            return wrap(value, width, size, false);
        }

        private List<String> wrap(String value, float width, float size, boolean bold) throws IOException {
            String text = normalized(value);
            List<String> lines = new ArrayList<>();
            for (String paragraph : text.split("\\n", -1)) {
                if (paragraph.isEmpty()) { lines.add(""); continue; }
                StringBuilder line = new StringBuilder();
                for (String word : paragraph.split("(?<= )", -1)) {
                    if (word.isEmpty()) continue;
                    if (!line.isEmpty() && width(line + word, size, bold) > width) {
                        lines.add(line.toString().stripTrailing());
                        line.setLength(0);
                    }
                    for (int offset = 0; offset < word.length();) {
                        int codePoint = word.codePointAt(offset);
                        String next = new String(Character.toChars(codePoint));
                        if (!line.isEmpty() && width(line + next, size, bold) > width) {
                            lines.add(line.toString().stripTrailing());
                            line.setLength(0);
                        }
                        line.append(next);
                        offset += Character.charCount(codePoint);
                    }
                }
                if (!line.isEmpty()) lines.add(line.toString().stripTrailing());
            }
            return lines;
        }

        private String normalized(String value) {
            return value == null ? "" : value.replace('\r', ' ').replace('\t', ' ');
        }

        private float width(String value, float size) throws IOException {
            return width(value, size, false);
        }

        private float width(String value, float size, boolean bold) throws IOException {
            return fontFor(value, bold).getStringWidth(value) / 1000 * size;
        }

        private PDFont fontFor(String value, boolean bold) throws IOException {
            PDFont preferred = bold ? boldFont : regular;
            try {
                preferred.getStringWidth(value);
                return preferred;
            } catch (IllegalArgumentException unsupported) {
                return fallback;
            }
        }

        private void fill(float[] color, float x, float bottom, float width, float height) throws IOException {
            stream.setNonStrokingColor(color[0], color[1], color[2]);
            stream.addRect(x, bottom, width, height);
            stream.fill();
        }

        private void darkText(float size, float x, float baseline, String text) throws IOException {
            darkText(size, x, baseline, text, false);
        }

        private void darkText(float size, float x, float baseline, String text, boolean bold) throws IOException {
            stream.setNonStrokingColor(20 / 255f, 43 / 255f, 66 / 255f);
            if (bold) stream.setStrokingColor(20 / 255f, 43 / 255f, 66 / 255f);
            text(size, x, baseline, text, bold);
        }

        private void whiteText(float size, float x, float baseline, String text) throws IOException {
            whiteText(size, x, baseline, text, false);
        }

        private void whiteText(float size, float x, float baseline, String text, boolean bold) throws IOException {
            stream.setNonStrokingColor(1f);
            if (bold) stream.setStrokingColor(1f);
            text(size, x, baseline, text, bold);
        }

        private void rightText(float size, float right, float baseline, String text, boolean white)
                throws IOException {
            rightText(size, right, baseline, text, white, false);
        }

        private void rightText(float size, float right, float baseline, String text, boolean white,
                boolean bold) throws IOException {
            float x = right - width(text, size, bold);
            if (white) whiteText(size, x, baseline, text, bold);
            else darkText(size, x, baseline, text, bold);
        }

        private void text(float size, float x, float baseline, String text, boolean bold) throws IOException {
            stream.beginText();
            PDFont chosen = fontFor(text, bold);
            stream.setFont(chosen, size);
            if (bold && chosen == fallback) {
                stream.setRenderingMode(RenderingMode.FILL_STROKE);
                stream.setLineWidth(0.25f);
            }
            stream.newLineAtOffset(x, baseline);
            stream.showText(text);
            stream.endText();
            if (bold && chosen == fallback) stream.setRenderingMode(RenderingMode.FILL);
        }

        private void close() throws IOException {
            if (stream != null) stream.close();
        }

        private void footers() throws IOException {
            int total = document.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                try (var footer = new PDPageContentStream(document, document.getPage(i),
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    footer.setNonStrokingColor(20 / 255f, 43 / 255f, 66 / 255f);
                    footer.beginText();
                    footer.setFont(regular, 8);
                    footer.newLineAtOffset(size.getWidth() - MARGIN - 84, 25);
                    footer.showText("Página " + (i + 1) + " de " + total);
                    footer.endText();
                }
            }
        }
    }
}
