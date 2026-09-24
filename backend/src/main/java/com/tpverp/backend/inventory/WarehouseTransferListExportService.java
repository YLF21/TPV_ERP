package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.io.*;
import java.nio.file.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class WarehouseTransferListExportService {
    private static final int MAX_ROWS = 50_000;
    private static final float[] WIDTHS = {108, 86, 118, 118, 172, 74, 44, 64};
    private final WarehouseTransferDocumentService transfers;
    private final WarehouseRepository warehouses;
    private final CurrentOrganization organization;

    public WarehouseTransferListExportService(WarehouseTransferDocumentService transfers,
            WarehouseRepository warehouses, CurrentOrganization organization) {
        this.transfers = transfers; this.warehouses = warehouses; this.organization = organization;
    }

    // Every batch belongs to the same database snapshot, even if new documents are created during the download.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] export(String format, WarehouseTransferListExportController.Filters filters) {
        if (!Set.of("pdf", "xlsx").contains(format)) throw new IllegalArgumentException("Formato no válido");
        var store = organization.currentStore();
        var copy = new Copy(filters.locale());
        var names = warehouses.findByStoreIdOrderByNombre(store.getId()).stream()
                .collect(Collectors.toMap(Warehouse::getId, Warehouse::getName));
        var dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of(store.getTimezone()));
        var rows = new ArrayList<List<String>>();
        for (int page = 0; ; page++) {
            var batch = transfers.list(page, 100, filters.status(), filters.sourceWarehouseId(),
                    filters.targetWarehouseId(), filters.from(), filters.before(), filters.search());
            for (var item : batch.items()) {
                if (rows.size() >= MAX_ROWS) throw new IllegalArgumentException("El informe supera 50000 documentos");
                rows.add(List.of(item.number() == null ? copy.status(WarehouseTransferDocument.Status.DRAFT) : item.number(),
                        dateFormat.format(item.createdAt()), names.getOrDefault(item.sourceWarehouseId(), ""),
                        names.getOrDefault(item.targetWarehouseId(), ""), Objects.toString(item.notes(), ""),
                        copy.status(item.status()), Long.toString(item.lineCount()), item.totalUnits().toPlainString()));
            }
            if (!batch.hasMore()) break;
            if (batch.items().isEmpty()) throw new IllegalStateException("No se pudo completar el informe");
        }
        try {
            return format.equals("xlsx") ? excel(copy, rows) : pdf(copy, Objects.toString(store.getNombreEfectivo(), ""), rows);
        } catch (IOException exception) { throw new IllegalStateException("No se pudo exportar el listado de traspasos", exception); }
    }

    private byte[] excel(Copy copy, List<List<String>> rows) throws IOException {
        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = book.createSheet(copy.title());
            var header = book.createCellStyle();
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var font = book.createFont(); font.setBold(true); header.setFont(font);
            var number = book.createCellStyle(); number.setDataFormat(book.createDataFormat().getFormat("0.###"));
            var heading = sheet.createRow(0);
            for (int i = 0; i < 8; i++) {
                var cell = heading.createCell(i); cell.setCellValue(copy.headers().get(i)); cell.setCellStyle(header);
                sheet.setColumnWidth(i, Math.round(WIDTHS[i] * 42));
            }
            for (int r = 0; r < rows.size(); r++) {
                var row = sheet.createRow(r + 1);
                for (int c = 0; c < 8; c++) {
                    var cell = row.createCell(c);
                    if (c >= 6) { cell.setCellValue(Double.parseDouble(rows.get(r).get(c))); cell.setCellStyle(number); }
                    else cell.setCellValue(rows.get(r).get(c));
                }
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, rows.size(), 0, 7));
            book.write(output); return output.toByteArray();
        }
    }

    private byte[] pdf(Copy copy, String store, List<List<String>> rows) throws IOException {
        try (var doc = new PDDocument(); var output = new ByteArrayOutputStream()) {
            boolean multilingual = (copy.locale() != null && copy.locale().startsWith("zh"))
                    || rows.stream().flatMap(List::stream).anyMatch(value -> value.codePoints()
                        .anyMatch(point -> Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN));
            PDFont font = font(doc, multilingual);
            var numbers = java.text.NumberFormat.getNumberInstance(Locale.forLanguageTag(copy.locale() == null ? "es" : copy.locale()));
            numbers.setMaximumFractionDigits(3);
            PDPageContentStream content = null;
            float y = 0;
            try {
                // Include an empty row to produce a usable report when no document matches.
                var printableRows = rows.isEmpty() ? List.of(Collections.nCopies(8, "")) : rows;
                for (var row : printableRows) {
                    var cells = new ArrayList<List<String>>();
                    for (int c = 0; c < 8; c++) cells.add(wrap(font,
                            c >= 6 && !row.get(c).isBlank() ? numbers.format(new java.math.BigDecimal(row.get(c))) : row.get(c), WIDTHS[c] - 8));
                    int height = cells.stream().mapToInt(List::size).max().orElse(1);
                    for (int offset = 0; offset < height;) {
                        if (content == null || y < 50) {
                            if (content != null) content.close();
                            var page = new PDPage(new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()));
                            doc.addPage(page); content = new PDPageContentStream(doc, page);
                            text(content, font, 15, 28, 562, copy.title());
                            text(content, font, 8, 28, 544, store);
                            text(content, font, 8, 760, 562, Integer.toString(doc.getNumberOfPages()));
                            y = 516;
                            content.setNonStrokingColor(.9f, .9f, .9f); content.addRect(28, y - 6, 784, 26); content.fill();
                            for (int c = 0; c < 8; c++) text(content, font, 8, x(c) + 4, y + 4, copy.headers().get(c));
                            y -= 6;
                        }
                        int count = Math.min(height - offset, Math.max(1, (int) ((y - 34) / 12) - 1));
                        float rowHeight = count * 12 + 8;
                        content.setStrokingColor(.75f, .75f, .75f);
                        for (int c = 0; c < 8; c++) {
                            content.addRect(x(c), y - rowHeight, WIDTHS[c], rowHeight); content.stroke();
                            for (int line = offset; line < Math.min(cells.get(c).size(), offset + count); line++)
                                text(content, font, 8, x(c) + 4, y - 12 - (line - offset) * 12, cells.get(c).get(line));
                        }
                        y -= rowHeight; offset += count;
                    }
                }
            } finally { if (content != null) content.close(); }
            doc.save(output); return output.toByteArray();
        }
    }

    private static float x(int column) { float value = 28; for (int c = 0; c < column; c++) value += WIDTHS[c]; return value; }
    private static void text(PDPageContentStream content, PDFont font, int size, float x, float y, String value) throws IOException {
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
            if (!line.isEmpty() && font.getStringWidth(line + character) * .008f > width) {
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
        for (var path : List.of(Path.of(System.getenv().getOrDefault("WINDIR", "C:/Windows"), "Fonts", "NotoSansSC-VF.ttf"),
                Path.of("/usr/share/fonts/truetype/noto/NotoSansSC-Regular.ttf"))) {
            if (Files.isRegularFile(path)) try (var input = Files.newInputStream(path)) { return PDType0Font.load(doc, input, true); }
        }
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }
    private record Copy(String locale) {
        String choose(String es, String en, String zh) { return locale != null && locale.startsWith("zh") ? zh
                : locale != null && locale.startsWith("en") ? en : es; }
        String title() { return choose("Traspasos de almacén", "Warehouse transfers", "仓库调拨"); }
        List<String> headers() { return List.of(choose("Número", "Number", "编号"), choose("Fecha", "Date", "日期"),
                choose("Origen", "Source", "来源仓库"), choose("Destino", "Destination", "目标仓库"),
                choose("Notas", "Notes", "备注"), choose("Estado", "Status", "状态"),
                choose("Líneas", "Lines", "行数"), choose("Unidades", "Units", "数量")); }
        String status(WarehouseTransferDocument.Status status) { return switch (status) {
            case DRAFT -> choose("Borrador", "Draft", "草稿");
            case CONFIRMED -> choose("Confirmado", "Confirmed", "已确认");
            case CANCELLED -> choose("Cancelado", "Cancelled", "已取消");
        }; }
    }
}
