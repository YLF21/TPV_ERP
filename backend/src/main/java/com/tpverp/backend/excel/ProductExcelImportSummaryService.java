package com.tpverp.backend.excel;

import static com.tpverp.backend.excel.ProductExcelImportPresentation.localizedErrorText;
import static com.tpverp.backend.excel.ProductExcelImportPresentation.isReferenceColumn;
import com.tpverp.backend.excel.ProductExcelImportPresentation.Labels;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.text.AttributedString;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.SheetUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Creates the exact effective preview projection as an XLSX download. */
@Service
public class ProductExcelImportSummaryService {
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final FontRenderContext TEXT_CONTEXT = new FontRenderContext(null, true, true);
    private static final List<String> FIELD_ORDER = List.of("code", "barcode", "name", "description", "comments", "quantity",
            "purchaseDiscountPercent", "productType", "familyId", "subfamilyId", "purchasePrice", "salePrice", "memberPrice",
            "wholesalePrice", "offerPrice", "offerDiscountPercent", "offerActive", "offerFrom", "offerUntil", "priceUseMode",
            "discountType", "taxId", "taxesIncluded", "packageQuantity", "stockMin", "stockMax", "barcode2");
    private final ProductExcelImportPreviewService preview;

    public ProductExcelImportSummaryService(ProductExcelImportPreviewService preview) {
        this.preview = preview;
    }

    public ExportedSummary export(MultipartFile file, ProductExcelImportPreviewService.PreviewRequest request) {
        return export(file, request, "es");
    }

    public ExportedSummary export(MultipartFile file, ProductExcelImportPreviewService.PreviewRequest request, String locale) {
        return exportPreview(file, request, locale, null, false, null);
    }

    public ExportedSummary export(MultipartFile file, SummaryRequest request, String locale) {
        ProductExcelImportPreviewService.PreviewRequest previewRequest = request == null ? null : request.preview();
        String expectedFingerprint = request == null ? null : request.expectedPreviewFingerprint();
        String view = request == null ? null : request.view();
        if (request != null && request.errorRows() != null && !"ERRORS".equalsIgnoreCase(view))
            throw columnError("VIEW_INVALID", view);
        if ("RAW".equalsIgnoreCase(view)) return exportRaw(file, request, normalizeLocale(locale));
        if (request != null && "ERRORS".equalsIgnoreCase(view) && request.errorRows() != null) {
            return exportErrorSnapshot(file, request, normalizeLocale(locale));
        }
        return exportPreview(file, previewRequest, locale, expectedFingerprint, true, request);
    }

    private ExportedSummary exportPreview(MultipartFile file,
            ProductExcelImportPreviewService.PreviewRequest request, String locale,
            String expectedFingerprint, boolean requireFingerprint, SummaryRequest summaryRequest) {
        String normalizedLocale = normalizeLocale(locale);
        boolean summaryView = summaryRequest == null || summaryRequest.view() == null
                || summaryRequest.view().isBlank() || "SUMMARY".equalsIgnoreCase(summaryRequest.view().trim());
        boolean importedOnly = summaryView && request != null && request.options() != null
                && Boolean.TRUE.equals(request.options().showOnlyImported());
        ViewSpec view = validateView(summaryRequest, importedOnly);
        // This option projects the summary, not product resolution or its concurrency snapshot.
        ProductExcelImportPreviewService.PreviewResult result = preview.preview(file, summaryPreview(request));
        // Row-level validation errors remain visible in the exported review;
        // only file/preview integrity errors prevent creating a workbook.
        if (!"ERRORS".equals(view.view()) && ((result.rows().isEmpty() && !result.errors().isEmpty())
                || result.errors().stream().anyMatch(error -> error.row() == null))) {
            throw new SummaryValidationException(result.fileName(), result.sha256(), result.errors());
        }
        if (requireFingerprint) validatePreviewFingerprint(result, expectedFingerprint);
        try {
            return new ExportedSummary(workbook(result, importedOnly, normalizedLocale, view), downloadName(result.fileName()));
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo generar el resumen XLSX", exception);
        }
    }

    private static ProductExcelImportPreviewService.PreviewRequest summaryPreview(
            ProductExcelImportPreviewService.PreviewRequest request) {
        if (request == null || request.options() == null || !Boolean.TRUE.equals(request.options().showOnlyImported())) return request;
        var options = request.options();
        var full = new ProductExcelImportPreviewService.PreviewOptions(options.globalValues(), options.valueSources(),
                false, options.context(), options.storeId(), options.companyId(), options.skipZeroPriceUpdate(),
                options.requireQuantity(), options.documentPriceSource());
        return new ProductExcelImportPreviewService.PreviewRequest(request.mapping(), request.edits(), full,
                request.storeId(), request.companyId(), request.expectedSha256(), request.startRow(),
                request.quantityColumn(), request.updateFields(), request.resolvedProducts());
    }

    private ExportedSummary exportErrorSnapshot(MultipartFile file, SummaryRequest request, String locale) {
        String context = request.preview() == null || request.preview().options() == null ? null : request.preview().options().context();
        if (!Set.of("STOCK", "WAREHOUSE_INPUT", "WAREHOUSE_OUTPUT").contains(context == null ? "" : context.toUpperCase(Locale.ROOT)))
            throw columnError("CONTEXT_INVALID", context);
        ViewSpec spec = validateView(request, false);
        if (!"ERRORS".equals(spec.view())) throw columnError("VIEW_INVALID", spec.view());
        if (request.errorRows().size() > 5_001) throw columnError("ROW_LIMIT", String.valueOf(request.errorRows().size()));
        long cells = 0, text = 0;
        for (Map<String, String> row : request.errorRows()) {
            if (row == null) throw columnError("COLUMN_INVALID", "null");
            if (row.keySet().stream().anyMatch(key -> !spec.columns().contains(key)))
                throw columnError("COLUMN_INVALID", "row keys");
            for (String column : spec.columns()) {
                String value = row.get(column);
                if (value != null && !value.isEmpty()) { cells++; text += value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length; }
                if (cells > 250_000 || text > 8L * 1024 * 1024) throw columnError("EDIT_LIMIT", String.valueOf(Math.max(cells, text)));
            }
        }
        Labels labels = Labels.forLocale(locale);
        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet(labels.sheetName()); sheet.createFreezePane(1, 1);
            var styles = new ReviewStyles(book);
            Row headerRow = sheet.createRow(0); headerRow.setHeightInPoints(36);
            for (int i = 0; i < spec.columns().size(); i++) {
                String key = spec.columns().get(i);
                Cell c = headerRow.createCell(i); c.setCellValue(labels.column(key)); c.setCellStyle(styles.header(key, false));
            }
            int physical = 1;
            for (Map<String, String> row : request.errorRows()) {
                List<String> values = spec.columns().stream().map(key -> row.getOrDefault(key, "")).toList();
                physical = writeLogicalRow(sheet, physical, values, styles.body("", false, false, physical % 2 == 0, true));
            }
            if (physical > 1) sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, physical - 1, 0, spec.columns().size() - 1));
            fitExportLayout(sheet, spec.columns(), false);
            book.write(output); return new ExportedSummary(output.toByteArray(), downloadName(file == null ? null : file.getOriginalFilename()));
        } catch (IOException exception) { throw new IllegalStateException("No se pudo generar el resumen XLSX", exception); }
    }

    private static void validatePreviewFingerprint(ProductExcelImportPreviewService.PreviewResult result,
            String expectedFingerprint) {
        ProductExcelImportPreviewService.ImportError error = null;
        if (expectedFingerprint == null || expectedFingerprint.isBlank()) {
            error = summaryError("CONCURRENCY_TOKEN_REQUIRED",
                    "El resumen necesita la huella de la vista previa mostrada",
                    "Huella SHA-256 de la vista previa", "Vuelve a generar la vista previa antes de exportar");
        } else if (!SHA256.matcher(expectedFingerprint).matches()) {
            error = summaryError("TOKEN_INVALID", "La huella de la vista previa no es válida",
                    "64 caracteres hexadecimales", "Vuelve a generar la vista previa antes de exportar");
        } else if (result.previewFingerprint() == null
                || !expectedFingerprint.equalsIgnoreCase(result.previewFingerprint())) {
            error = summaryError("VERSION_STALE",
                    "Los datos han cambiado desde la vista previa mostrada",
                    "La vista previa actual", "Vuelve a generar la vista previa y exporta de nuevo");
        }
        if (error != null) {
            throw new SummaryValidationException(result.fileName(), result.sha256(), List.of(error));
        }
    }

    private static ProductExcelImportPreviewService.ImportError summaryError(String code, String reason,
            String accepted, String fix) {
        return new ProductExcelImportPreviewService.ImportError(code, null, null, null, null,
                reason, accepted, fix);
    }

    static String normalizeLocale(String requested) {
        if (requested == null || requested.isBlank()) return "es";
        if (requested.length() > 8) {
            throw new ProductExcelImportReadService.ProductExcelImportException(
                    "LOCALE_INVALID", "El idioma del resumen no es válido", null, null,
                    "locale", requested, null);
        }
        String normalized = requested.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("es", "en", "zh").contains(normalized)) {
            throw new ProductExcelImportReadService.ProductExcelImportException(
                    "LOCALE_INVALID", "El idioma del resumen no es válido", null, null,
                    "locale", requested, null);
        }
        return normalized;
    }

    private byte[] workbook(ProductExcelImportPreviewService.PreviewResult result, boolean importedOnly, String locale, ViewSpec spec) throws IOException {
        if (!spec.explicit()) return legacyWorkbook(result, importedOnly, locale);
        List<ProductExcelImportPreviewService.PreviewRow> rows = (result.sourceRows() == null || result.sourceRows().isEmpty())
                ? result.rows() : result.sourceRows();
        rows = filterRows(rows, spec.view());
        if ("ERRORS".equals(spec.view()) && result.errors() != null && !result.errors().isEmpty()) {
            rows = new ArrayList<>(rows);
            rows.add(new ProductExcelImportPreviewService.PreviewRow(0, List.of(0), "ERROR", Map.of(), null, null, Map.of(), result.errors(), false, false, null));
        }
        Labels labels = Labels.forLocale(locale);
        List<String> columns = spec.columns().stream().map(labels::column).toList();
        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = book.createSheet(labels.sheetName());
            sheet.createFreezePane(1, 1);
            var styles = new ReviewStyles(book);
            boolean comparison = Set.of("SUMMARY", "IMPORTABLE").contains(spec.view());
            Row headerRow = sheet.createRow(0);
            headerRow.setHeightInPoints(36);
            for (int index = 0; index < columns.size(); index++) {
                Cell cell = headerRow.createCell(index);
                cell.setCellValue(columns.get(index));
                cell.setCellStyle(styles.header(spec.columns().get(index), comparison));
            }
            int physicalRow = 1;
            for (var source : rows) {
                List<String> values = spec.columns().stream().map(column -> columnValue(source, column, labels)).toList();
                int first = physicalRow;
                physicalRow = writeLogicalRow(sheet, physicalRow, values, styles.body("", false, false, false, false));
                for (int physical = first; physical < physicalRow; physical++) {
                    for (int column = 0; column < spec.columns().size(); column++) {
                        String key = spec.columns().get(column);
                        sheet.getRow(physical).getCell(column).setCellStyle(styles.body(key, comparison,
                                comparison && differs(source, key), first % 2 == 0, "ERROR".equals(source.classification())));
                    }
                }
            }
            fitExportLayout(sheet, spec.columns(), false);
            if (physicalRow > 1) sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, physicalRow - 1, 0, columns.size() - 1));
            book.write(output);
            return output.toByteArray();
        }
    }

    private byte[] legacyWorkbook(ProductExcelImportPreviewService.PreviewResult result, boolean importedOnly, String locale) throws IOException {
        boolean showDatabase = !importedOnly;
        boolean showChanges = !importedOnly && result.rows().stream().anyMatch(row -> !row.changes().isEmpty());
        boolean showErrors = result.rows().stream().anyMatch(row -> !row.errors().isEmpty());
        Labels labels = Labels.forLocale(locale);
        List<String> columns = new ArrayList<>(List.of(labels.row(), labels.status(), labels.excelData()));
        if (showDatabase) columns.add(labels.databaseData());
        if (showChanges) columns.add(labels.beforeAfter());
        if (showErrors) columns.add(labels.errorDetail());
        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = book.createSheet(labels.sheetName()); sheet.createFreezePane(0, 1);
            CellStyle header = headerStyle(book), body = bodyStyle(book); Row headerRow = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) { Cell c = headerRow.createCell(i); c.setCellValue(columns.get(i)); c.setCellStyle(header); }
            int physicalRow = 1;
            for (var source : result.rows()) {
                List<String> values = new ArrayList<>(List.of(source.rowNumbers().stream().map(String::valueOf).reduce((a,b)->a+", "+b).orElse(String.valueOf(source.rowNumber())), labels.statusValue(source.classification(), source.purchasePriceChanged()), valuesText(source.excelData(), Set.of(), labels)));
                if (showDatabase) values.add(valuesText(source.databaseData(), Set.of("id", "version"), labels));
                if (showChanges) values.add(changesText(source.changes(), labels));
                if (showErrors) values.add(errorText(source.errors(), labels));
                physicalRow = writeLogicalRow(sheet, physicalRow, values, body);
            }
            List<String> layoutKeys = new ArrayList<>(List.of("rowNumber", "status"));
            while (layoutKeys.size() < columns.size()) layoutKeys.add("legacy." + layoutKeys.size());
            fitExportLayout(sheet, layoutKeys, false);
            book.write(output); return output.toByteArray();
        }
    }

    private static List<ProductExcelImportPreviewService.PreviewRow> filterRows(List<ProductExcelImportPreviewService.PreviewRow> rows, String view) {
        return switch (view) {
            case "SUMMARY" -> rows;
            case "MISSING" -> rows.stream().filter(row -> "MISSING".equals(row.existence())).toList();
            case "PURCHASE_CHANGED" -> rows.stream().filter(row -> "EXISTING".equals(row.existence()) && row.purchasePriceChanged()).toList();
            case "IMPORTABLE" -> rows.stream().filter(row -> "EXISTING".equals(row.existence())).toList();
            case "ERRORS" -> rows.stream().filter(row -> row.errors() != null && !row.errors().isEmpty()).toList();
            default -> rows;
        };
    }

    private static String columnValue(ProductExcelImportPreviewService.PreviewRow row, String key, Labels labels) {
        if ("rowNumber".equals(key)) return String.valueOf(row.rowNumber());
        if ("status".equals(key)) return labels.statusValue(row.classification(), row.purchasePriceChanged());
        if ("errors".equals(key)) return errorText(row.errors() == null ? List.of() : row.errors(), labels);
        if (key.startsWith("excel.")) return scalar(row.excelData(), key.substring(6));
        if (key.startsWith("current.")) return scalar(row.databaseData(), key.substring(8));
        return "";
    }

    private static String scalar(Map<String, Object> values, String key) {
        if (values == null || values.get(key) == null) return "";
        return String.valueOf(values.get(key));
    }

    private static ViewSpec validateView(SummaryRequest request, boolean importedOnly) {
        String view = request == null || request.view() == null || request.view().isBlank() ? "SUMMARY" : request.view().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("SUMMARY", "MISSING", "PURCHASE_CHANGED", "IMPORTABLE", "ERRORS").contains(view))
            throw columnError("VIEW_INVALID", view);
        if (request == null || (request.view() == null && request.columns() == null)) return new ViewSpec(view, List.of(), false);
        if (request.columns() == null || request.columns().isEmpty()) throw columnError("COLUMN_INVALID", "columns");
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String column : request.columns()) {
            if (column == null || !seen.add(column)) throw columnError("COLUMN_INVALID", column);
            boolean valid = "rowNumber".equals(column) || "status".equals(column) || "errors".equals(column)
                    || column.startsWith("excel.") || column.startsWith("current.");
            if (!valid || column.contains("..") || (column.startsWith("excel.") && !isExportField(column.substring(6)))
                    || (column.startsWith("current.") && !isExportField(column.substring(8)))) throw columnError("COLUMN_INVALID", column);
            if (importedOnly && column.startsWith("current.")) throw columnError("COLUMN_INVALID", column);
            String context = request == null || request.preview() == null || request.preview().options() == null
                    ? null : request.preview().options().context();
            if ("STOCK".equalsIgnoreCase(context) && (column.equals("excel.quantity") || column.equals("current.quantity")))
                throw columnError("COLUMN_INVALID", column);
        }
        return new ViewSpec(view, List.copyOf(seen), true);
    }

    private static ProductExcelImportReadService.ProductExcelImportException columnError(String code, String value) {
        return new ProductExcelImportReadService.ProductExcelImportException(code, "La vista o columna no es válida", null, null, "columns", value, null);
    }

    private record ViewSpec(String view, List<String> columns, boolean explicit) { }

    private static boolean isExportField(String key) {
        // Older clients may still request this column; new importers derive it from code.
        return FIELD_ORDER.contains(key) || "supplierReference".equals(key);
    }

    /** Writes one logical preview row, splitting long text across continuation rows. */
    private static int writeLogicalRow(Sheet sheet, int firstRow, List<String> values, CellStyle body) {
        List<List<String>> chunks = values.stream().map(ProductExcelImportSummaryService::safeCellChunks).toList();
        int rowCount = chunks.stream().mapToInt(List::size).max().orElse(1);
        for (int chunkIndex = 0; chunkIndex < rowCount; chunkIndex++) {
            Row row = sheet.createRow(firstRow + chunkIndex);
            row.setHeightInPoints(24f);
            for (int column = 0; column < chunks.size(); column++) {
                List<String> columnChunks = chunks.get(column);
                String value = chunkIndex < columnChunks.size() ? columnChunks.get(chunkIndex) : "";
                if (chunkIndex > 0 && column < 2) value = columnChunks.get(0);
                writeCell(row.createCell(column), value, body);
            }
        }
        return firstRow + rowCount;
    }

    private static List<String> safeCellChunks(String value) {
        if (value == null || value.isEmpty()) return List.of("");
        List<String> chunks = new ArrayList<>((value.length() / ProductExcelImportReadService.MAX_CELL_CHARACTERS) + 1);
        for (int offset = 0; offset < value.length();) {
            int end = Math.min(value.length(), offset + ProductExcelImportReadService.MAX_CELL_CHARACTERS);
            // Keep a UTF-16 surrogate pair together while retaining the Excel
            // 32,767-code-unit ceiling.
            if (end < value.length() && end > offset
                    && Character.isHighSurrogate(value.charAt(end - 1))
                    && Character.isLowSurrogate(value.charAt(end))) end--;
            if (end == offset) end = Math.min(value.length(), offset + 1);
            chunks.add(value.substring(offset, end));
            offset = end;
        }
        return chunks;
    }

    private static String valuesText(Map<String, Object> values, Set<String> excluded, Labels labels) {
        if (values == null) return "-";
        return orderedEntries(values).stream().filter(entry -> !excluded.contains(entry.getKey()) && entry.getValue() != null
                && !String.valueOf(entry.getValue()).isBlank()).map(entry -> labels.field(entry.getKey()) + ": " + entry.getValue())
                .reduce((left, right) -> left + "\n" + right).orElse("-");
    }

    @SuppressWarnings("unchecked")
    private static String changesText(Map<String, Object> changes, Labels labels) {
        if (changes == null || changes.isEmpty()) return "-";
        return orderedEntries(changes).stream().map(entry -> {
            Map<String, Object> change = entry.getValue() instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
            return labels.field(entry.getKey()) + ": " + String.valueOf(change.getOrDefault("before", "-"))
                    + " → " + String.valueOf(change.getOrDefault("after", "-"));
        }).reduce((left, right) -> left + "\n" + right).orElse("-");
    }

    private static String errorText(List<ProductExcelImportPreviewService.ImportError> errors, Labels labels) {
        return localizedErrorText(errors, labels.locale());
    }


    private static List<Map.Entry<String, Object>> orderedEntries(Map<String, Object> values) {
        if (values == null) return List.of();
        List<Map.Entry<String, Object>> ordered = new ArrayList<>();
        for (String key : FIELD_ORDER) if (values.containsKey(key)) ordered.add(new java.util.AbstractMap.SimpleImmutableEntry<>(key, values.get(key)));
        values.forEach((key, value) -> { if (FIELD_ORDER.stream().noneMatch(key::equals)) ordered.add(new java.util.AbstractMap.SimpleImmutableEntry<>(key, value)); });
        return ordered;
    }


    private static void writeCell(Cell cell, Object value) {
        if (value == null) return;
        if (value instanceof Number number) cell.setCellValue(number.doubleValue());
        else if (value instanceof Boolean bool) cell.setCellValue(bool);
        else if (value instanceof BigDecimal decimal) cell.setCellValue(decimal.doubleValue());
        else cell.setCellValue(String.valueOf(value));
    }

    private static CellStyle headerStyle(XSSFWorkbook book) {
        CellStyle style = book.createCellStyle();
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setWrapText(true);
        style.setBorderBottom(BorderStyle.THIN);
        Font font = book.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        return style;
    }

    private static CellStyle bodyStyle(XSSFWorkbook book) {
        CellStyle style = book.createCellStyle();
        style.setWrapText(true);
        style.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.TOP);
        return style;
    }

    private static String downloadName(String source) {
        String base = source == null ? "importacion" : source.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isBlank()) base = "importacion";
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        if (stem.length() > 120) stem = stem.substring(0, 120);
        return stem + "-resumen.xlsx";
    }

    private ExportedSummary exportRaw(MultipartFile file, SummaryRequest request, String locale) {
        ProductExcelImportPreviewService.PreviewRequest config = request == null ? null : request.preview();
        ProductExcelImportReadService.ReadResult read = preview.readRaw(file, config);
        List<List<ProductExcelImportReadService.CellView>> rows = read.rows();
        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            Labels labels = Labels.forLocale(locale); Sheet sheet = book.createSheet(labels.rawSheetName());
            sheet.createFreezePane(0, 1);
            var styles = new ReviewStyles(book); int rowIndex = 0;
            for (List<ProductExcelImportReadService.CellView> source : rows) {
                Row target = sheet.createRow(rowIndex++);
                target.setHeightInPoints(rowIndex == 1 ? 24 : 21);
                for (int column = 0; column < source.size(); column++) {
                    var cell = source.get(column);
                    writeCell(target.createCell(column), cell == null ? "" : cell.value(), rowIndex == 1 ? styles.header("", false)
                            : styles.body("", false, false, rowIndex % 2 == 0, cell != null && cell.errorCode() != null));
                }
            }
            List<String> layoutKeys = new ArrayList<>();
            int columnCount = rows.stream().mapToInt(List::size).max().orElse(0);
            for (int column = 0; column < columnCount; column++) layoutKeys.add("raw." + column);
            if (config != null && config.mapping() != null) config.mapping().forEach((field, letter) -> {
                int index = columnIndex(letter);
                if ((field.equals("name") || field.equals("description")) && index >= 0 && index < layoutKeys.size())
                    layoutKeys.set(index, field);
            });
            fitExportLayout(sheet, layoutKeys, true);
            book.write(output); return new ExportedSummary(output.toByteArray(), downloadName(read.fileName()).replace("-resumen.xlsx", "-raw.xlsx"));
        } catch (IOException exception) { throw new IllegalStateException("No se pudo generar el XLSX RAW", exception); }
    }

    private static int columnIndex(String column) {
        if (column == null || column.isBlank()) return -1;
        int result = 0;
        for (char c : column.trim().toUpperCase(Locale.ROOT).toCharArray()) {
            if (c < 'A' || c > 'Z') return -1;
            result = result * 26 + c - 'A' + 1;
            if (result > ProductExcelImportReadService.MAX_COLUMNS) return -1;
        }
        return result - 1;
    }

    private static void writeCell(Cell cell, Object value, CellStyle style) {
        writeCell(cell, value);
        cell.setCellStyle(style);
    }

    /** Measure data, not comparison headings; matching BD/Excel attributes share a width. */
    private static void fitExportLayout(Sheet sheet, List<String> keys, boolean raw) {
        Map<String, Integer> widths = new java.util.HashMap<>();
        for (int column = 0; column < keys.size(); column++) {
            String field = layoutField(keys.get(column));
            int minimum = switch (field) {
                case "rowNumber" -> 6;
                case "name", "description" -> 8;
                case "code", "barcode", "barcode2", "supplierReference", "errors" -> 18;
                case "offerFrom", "offerUntil" -> 12;
                case "offerActive", "discountType", "taxesIncluded", "taxId", "productType", "priceUseMode",
                        "purchaseDiscountPercent", "offerDiscountPercent" -> 10;
                case "status" -> 16;
                default -> raw ? 8 : 12;
            };
            int maximum = Set.of("name", "description", "errors").contains(field)
                    || field.startsWith("raw.") || field.startsWith("legacy.") ? 50 : 255;
            // POI uses the workbook's font metrics, including wide CJK glyphs and bold differences.
            double measured = SheetUtil.getColumnWidth(sheet, column, false, raw ? 0 : 1, sheet.getLastRowNum());
            int width = (int) Math.ceil(Math.min(maximum, Math.max(minimum, measured + 2)) * 256);
            widths.merge(field, width, Math::max);
        }
        for (int column = 0; column < keys.size(); column++)
            sheet.setColumnWidth(column, widths.get(layoutField(keys.get(column))));

        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        Map<Integer, java.awt.Font> fonts = new java.util.HashMap<>();
        float defaultCharWidth = SheetUtil.getDefaultCharWidthAsFloat(sheet.getWorkbook());
        for (Row row : sheet) {
            float height = row.getRowNum() == 0 ? 36 : 24;
            for (Cell cell : row) {
                String value = formatter.formatCellValue(cell);
                if (value.isEmpty()) continue;
                float width = sheet.getColumnWidth(cell.getColumnIndex()) / 256f * defaultCharWidth - 5;
                int fontIndex = cell.getCellStyle().getFontIndex();
                java.awt.Font font = fonts.computeIfAbsent(fontIndex, index -> {
                    Font source = sheet.getWorkbook().getFontAt(index);
                    return new java.awt.Font(source.getFontName(), (source.getBold() ? java.awt.Font.BOLD : 0)
                            | (source.getItalic() ? java.awt.Font.ITALIC : 0), source.getFontHeightInPoints());
                });
                height = Math.max(height, wrappedTextHeight(value, font, Math.max(1, width)));
                if (height >= 409.5f) break; // Excel's row-height limit; never truncate the stored text.
            }
            row.setHeightInPoints(Math.min(409.5f, height));
        }
    }

    private static String layoutField(String key) {
        return key.startsWith("current.") ? key.substring(8) : key.startsWith("excel.") ? key.substring(6) : key;
    }

    private static float wrappedTextHeight(String value, java.awt.Font font, float width) {
        float height = 6;
        for (String line : value.split("\\r\\n|\\r|\\n", -1)) {
            if (line.isEmpty()) { height += font.getSize2D() * 1.25f; continue; }
            AttributedString text = new AttributedString(line);
            text.addAttribute(TextAttribute.FONT, font);
            var measurer = new LineBreakMeasurer(text.getIterator(), TEXT_CONTEXT);
            while (measurer.getPosition() < line.length()) {
                var layout = measurer.nextLayout(width);
                height += layout.getAscent() + layout.getDescent() + layout.getLeading();
                if (height >= 409.5f) return 409.5f;
            }
        }
        return height;
    }

    /** Presentation differences do not depend on update checkboxes or zero-price write protection. */
    private static boolean differs(ProductExcelImportPreviewService.PreviewRow row, String key) {
        if (isReferenceColumn(key)) return false;
        int dot = key.indexOf('.');
        if (dot < 0 || row.databaseData() == null || row.excelData() == null) return false;
        String field = key.substring(dot + 1);
        if (!row.databaseData().containsKey(field) || !row.excelData().containsKey(field)) return false;
        return !comparable(field, row.databaseData().get(field)).equals(comparable(field, row.excelData().get(field)));
    }

    private static String comparable(String field, Object value) {
        String text = value == null ? "" : value.toString().trim();
        if (text.isEmpty()) return "";
        if (field.equals("productType")) return ProductExcelImportPreviewService.normalizeProductType(text);
        if (field.equals("priceUseMode")) return switch (text) {
            case "NORMAL" -> "1"; case "MEMBER_PRICE" -> "2"; case "OFFER_PRICE" -> "3"; case "OFFER_DISCOUNT" -> "4"; default -> text;
        };
        if (field.endsWith("Price") || Set.of("purchaseDiscountPercent", "offerDiscountPercent", "packageQuantity", "stockMin", "stockMax", "taxId").contains(field)) {
            try { return new BigDecimal(text.replaceAll("\\s*%$", "").replace(',', '.')).stripTrailingZeros().toPlainString(); }
            catch (NumberFormatException ignored) { /* Invalid imported values must remain visible. */ }
        }
        return text;
    }

    /** Small workbook-scoped palette; never allocate a style for every cell. */
    private static final class ReviewStyles {
        private final XSSFWorkbook book;
        private final Map<String, CellStyle> cache = new java.util.HashMap<>();
        private final Font regular;
        private final Font bold;
        private final Font white;
        ReviewStyles(XSSFWorkbook book) {
            this.book = book;
            regular = book.createFont(); regular.setFontName("Arial"); regular.setFontHeightInPoints((short) 10);
            ((org.apache.poi.xssf.usermodel.XSSFFont) regular).setColor(color("001B3F"));
            bold = book.createFont(); bold.setFontName("Arial"); bold.setFontHeightInPoints((short) 10); bold.setBold(true);
            ((org.apache.poi.xssf.usermodel.XSSFFont) bold).setColor(color("001B3F"));
            white = book.createFont(); white.setFontName("Arial"); white.setFontHeightInPoints((short) 10); white.setBold(true); white.setColor(IndexedColors.WHITE.getIndex());
        }
        CellStyle header(String key, boolean comparison) {
            String source = source(key, comparison);
            return cache.computeIfAbsent("header:" + source, ignored -> {
                var style = style(source.equals("current") ? "334D70" : source.equals("excel") ? "205B3B" : "263F63");
                style.setFont(white); return style;
            });
        }
        CellStyle body(String key, boolean comparison, boolean changed, boolean even, boolean error) {
            String source = source(key, comparison);
            return cache.computeIfAbsent(source + ":" + changed + ":" + even + ":" + error, ignored -> {
                String fill = source.equals("current") ? (even ? "E7EEF8" : "EEF3FA")
                        : source.equals("excel") ? (changed ? "FFF0BD" : even ? "E8F5EB" : "F0F8F2")
                        : error ? "FFF1F0" : even ? "EEF4F8" : "FFFFFF";
                var style = style(fill); style.setFont(changed ? bold : regular);
                if (changed) { style.setBorderLeft(BorderStyle.MEDIUM); style.setLeftBorderColor(color("916000")); }
                return style;
            });
        }
        private org.apache.poi.xssf.usermodel.XSSFCellStyle style(String fill) {
            var style = book.createCellStyle();
            style.setFillForegroundColor(color(fill)); style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setWrapText(true); style.setAlignment(HorizontalAlignment.LEFT);
            style.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
            style.setBorderBottom(BorderStyle.THIN); style.setBottomBorderColor(color("D9E2EC"));
            style.setBorderRight(BorderStyle.THIN); style.setRightBorderColor(color("D9E2EC"));
            return style;
        }
        private static String source(String key, boolean comparison) {
            return !comparison || isReferenceColumn(key) ? "" : key.startsWith("current.") ? "current" : key.startsWith("excel.") ? "excel" : "";
        }
        private static XSSFColor color(String hex) { return new XSSFColor(java.util.HexFormat.of().parseHex(hex), null); }
    }


    public record ExportedSummary(byte[] bytes, String fileName) { public String contentType() { return XLSX; } }
    public record SummaryRequest(ProductExcelImportPreviewService.PreviewRequest preview,
            String expectedPreviewFingerprint, String view, List<String> columns, List<Map<String, String>> errorRows) {
        public SummaryRequest(ProductExcelImportPreviewService.PreviewRequest preview, String expectedPreviewFingerprint) {
            this(preview, expectedPreviewFingerprint, null, null, null);
        }
        public SummaryRequest(ProductExcelImportPreviewService.PreviewRequest preview, String expectedPreviewFingerprint,
                String view, List<String> columns) { this(preview, expectedPreviewFingerprint, view, columns, null); }
        public SummaryRequest {
            columns = columns == null ? null : new ArrayList<>(columns);
            errorRows = errorRows == null ? null : new ArrayList<>(errorRows);
        }
    }
    public static class SummaryValidationException extends RuntimeException {
        private final String fileName;
        private final String sha256;
        private final List<ProductExcelImportPreviewService.ImportError> errors;
        SummaryValidationException(String fileName, String sha256, List<ProductExcelImportPreviewService.ImportError> errors) {
            super("La vista previa contiene errores y no se puede exportar");
            this.fileName = fileName; this.sha256 = sha256; this.errors = List.copyOf(errors);
        }
        public String fileName() { return fileName; }
        public String sha256() { return sha256; }
        public List<ProductExcelImportPreviewService.ImportError> errors() { return errors; }
    }
}
