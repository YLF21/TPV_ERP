package com.tpverp.backend.inventory;

import static com.tpverp.backend.inventory.SaasProductSalesHistoryApi.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.document.template.RenderedDocumentView.RenderedArtifact;
import com.tpverp.backend.document.template.SaasProductSalesHistoryJasperRenderer;
import com.tpverp.backend.excel.StockSalesHistoryExportRequest.Column;
import com.tpverp.backend.organization.CurrentOrganization;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

/** Presentation only: both files consume the same bounded, consistent central export. */
@Service
public class SaasProductSalesHistoryExports {
    private static final Set<String> DETAIL = SaasProductSalesHistoryService.SORTS;
    private static final Set<String> COMPARISON = Set.of("store", "quantitySold", "quantityReturned", "netQuantity", "netAmount", "currency");
    private static final Set<String> NUMERIC = Set.of("quantity", "unitPrice", "discount", "total", "quantitySold", "quantityReturned", "netQuantity", "netAmount");
    private final CurrentOrganization organization;
    private final SaasProductSalesHistoryJasperRenderer pdf;
    public SaasProductSalesHistoryExports(CurrentOrganization organization, SaasProductSalesHistoryJasperRenderer pdf) {
        this.organization = organization; this.pdf = pdf;
    }
    public void validate(ExportRequest request) {
        if (request == null || request.labels() == null || request.columns() == null || request.columns().isEmpty()
                || request.columns().size() > 12 || request.view() != null && !Set.of("detail", "comparison").contains(request.view())
                || request.locale() != null && !Set.of("es", "en", "zh").contains(request.locale())) invalid();
        var allowed = comparison(request) ? COMPARISON : DETAIL;
        var keys = new HashSet<String>();
        for (var column : request.columns()) {
            if (column == null || !allowed.contains(column.key()) || !keys.add(column.key())
                    || column.label() == null || column.label().isBlank() || column.label().length() > 100) invalid();
        }
        if (request.comparisonSortBy() != null && !COMPARISON.contains(request.comparisonSortBy())
                || request.comparisonSortDirection() != null && !Set.of("asc", "desc").contains(request.comparisonSortDirection())) invalid();
    }
    public byte[] excel(Product product, ExportRequest request, JsonNode response) {
        var columns = columns(request); var values = rows(request, response);
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Historial"); sheet.setDisplayGridlines(false);
            var font = workbook.createFont(); font.setFontName("Arial"); font.setFontHeightInPoints((short) 10); font.setColor(IndexedColors.BLACK.getIndex());
            var boldFont = workbook.createFont(); boldFont.setFontName("Arial"); boldFont.setFontHeightInPoints((short) 10); boldFont.setBold(true); boldFont.setColor(IndexedColors.BLACK.getIndex());
            var text = workbook.createCellStyle(); text.setFont(font); text.setWrapText(true); text.setVerticalAlignment(VerticalAlignment.CENTER);
            var bold = workbook.createCellStyle(); bold.cloneStyleFrom(text); bold.setFont(boldFont);
            var header = workbook.createCellStyle(); header.cloneStyleFrom(bold); header.setBorderBottom(BorderStyle.THIN);
            var quantity = workbook.createCellStyle(); quantity.cloneStyleFrom(text); quantity.setAlignment(HorizontalAlignment.RIGHT);
            quantity.setDataFormat(workbook.createDataFormat().getFormat("#,##0.################"));
            var money = workbook.createCellStyle(); money.cloneStyleFrom(quantity); money.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00##############"));
            int layout = Math.max(2, columns.size());
            for (int i = 0; i < layout; i++) sheet.setColumnWidth(i, (i < columns.size() && !NUMERIC.contains(columns.get(i).key()) ? 26 : 18) * 256);
            writeMerged(sheet, 0, layout, request.labels().title(), bold, 30);
            var metadata = metadata(product, request, response);
            writeMerged(sheet, 1, layout, metadata, text, Math.min(409, 18 * metadata.split("\n", -1).length + 12));
            int headerRow = 3;
            var headings = sheet.createRow(headerRow); headings.setHeightInPoints(32);
            for (int i = 0; i < columns.size(); i++) write(headings.createCell(i), columns.get(i).label(), header);
            int rowIndex = headerRow + 1;
            for (var row : values) {
                var target = sheet.createRow(rowIndex++); float height = 24;
                for (int i = 0; i < columns.size(); i++) {
                    var key = columns.get(i).key(); var cell = target.createCell(i);
                    Object value = value(row, key);
                    if (value instanceof BigDecimal number && number.stripTrailingZeros().precision() <= 15) {
                        cell.setCellValue(number.doubleValue()); cell.setCellStyle(key.equals("total") || key.equals("netAmount") ? money : quantity);
                    } else {
                        String label = value instanceof BigDecimal number ? number.toPlainString() : String.valueOf(value);
                        write(cell, label, text);
                        height = Math.max(height, 16 * (float) Math.ceil(Math.max(1, label.length()) / (double) (sheet.getColumnWidth(i) / 256 - 2)) + 8);
                    }
                }
                target.setHeightInPoints(Math.min(409, height));
            }
            sheet.setAutoFilter(new CellRangeAddress(headerRow, Math.max(headerRow, rowIndex - 1), 0, columns.size() - 1));
            sheet.createFreezePane(0, headerRow + 1);
            String totals = totals(request, response);
            writeMerged(sheet, rowIndex + 1, layout, totals, bold, Math.min(409, 20 * totals.split("\n", -1).length + 8));
            workbook.write(output); return output.toByteArray();
        } catch (java.io.IOException exception) { throw new IllegalStateException("product_history_excel_failed", exception); }
    }
    public PdfResponse pdf(Product product, ExportRequest request, JsonNode response) {
        var columns = columns(request);
        var cells = rows(request, response).stream().map(row -> columns.stream().map(column -> {
            Object value = value(row, column.key());
            return value instanceof BigDecimal number ? formatted(number, column.key(), request.locale()) : String.valueOf(value);
        }).toList()).toList();
        byte[] bytes = pdf.render(request.labels().title(), metadata(product, request, response), totals(request, response),
                word(request.locale(), "Página", "Page", "页"), columns.stream().map(Column::label).toList(),
                columns.stream().map(column -> weight(column.key())).toList(),
                columns.stream().map(column -> NUMERIC.contains(column.key())).toList(), cells);
        return new PdfResponse(new RenderedArtifact("application/pdf", Base64.getEncoder().encodeToString(bytes)), "historial-ventas-saas.pdf");
    }
    private List<Column> columns(ExportRequest request) {
        var columns = new ArrayList<>(request.columns());
        if (columns.stream().noneMatch(column -> column.key().equals("currency"))) columns.add(new Column("currency", word(request.locale(), "Moneda", "Currency", "币种")));
        return columns;
    }
    private List<JsonNode> rows(ExportRequest request, JsonNode response) {
        var rows = new ArrayList<JsonNode>(); response.path(comparison(request) ? "comparison" : "items").forEach(rows::add);
        if (comparison(request)) {
            var key = request.comparisonSortBy() == null ? "netQuantity" : request.comparisonSortBy();
            Comparator<JsonNode> order = NUMERIC.contains(key)
                    ? Comparator.comparing(row -> SaasProductSalesHistoryService.decimal(row, key))
                    : Comparator.comparing(row -> String.valueOf(value(row, key)));
            if (!"asc".equals(request.comparisonSortDirection())) order = order.reversed();
            rows.sort(order.thenComparing(row -> row.path("storeId").asText()).thenComparing(row -> row.path("currency").asText()));
        }
        return rows;
    }
    private Object value(JsonNode row, String key) {
        if (NUMERIC.contains(key)) return SaasProductSalesHistoryService.decimal(row, switch (key) {
            case "discount" -> "discountPercent"; case "total" -> "lineTotal"; default -> key;
        });
        if (key.equals("occurredAt")) {
            if (!row.path("occurredAt").isTextual()) return row.path("businessDate").asText("");
            return DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm").withZone(ZoneId.of(organization.currentStore().getTimezone()))
                    .format(Instant.parse(row.path("occurredAt").asText()));
        }
        if (key.equals("document")) return row.path("documentType").asText() + " " + row.path("documentNumber").asText();
        String field = switch (key) { case "customer" -> "customerName"; case "user" -> "userName";
            case "store" -> "storeName"; case "warehouse" -> "warehouseName"; default -> key; };
        var value = row.path(field); return value.isTextual() && !value.asText().isBlank() ? value.asText() : "-";
    }
    private String metadata(Product product, ExportRequest request, JsonNode response) {
        var filters = SaasProductSalesHistoryService.query(request.filters());
        var selected = request.storeIds() == null ? List.<java.util.UUID>of() : request.storeIds();
        var stores = new ArrayList<String>();
        response.path("stores").forEach(store -> { if (selected.contains(java.util.UUID.fromString(store.path("id").asText()))) stores.add(store.path("name").asText(store.path("code").asText())); });
        String locale = request.locale();
        var result = organization.currentCompany().getRazonSocial() + "\n" + request.labels().product() + ": " + product.getName()
                + " | " + request.labels().code() + ": " + product.getCode() + "\n" + request.labels().period() + ": "
                + (filters.get("from") == null ? "-" : filters.get("from")) + " - " + (filters.get("to") == null ? "-" : filters.get("to"))
                + " | " + request.labels().status() + ": " + (filters.get("status") == null ? request.labels().allStatuses() : filters.get("status"))
                + "\n" + word(locale, "Tiendas", "Stores", "门店") + ": " + (selected.isEmpty() ? word(locale, "Todas", "All", "全部") : String.join(", ", stores))
                + " | " + word(locale, "Zona horaria", "Time zone", "时区") + ": " + organization.currentStore().getTimezone()
                + "\n" + word(locale, "Solo datos recibidos en SaaS", "Only data received in SaaS", "仅显示SaaS已接收的数据");
        if (response.path("incompleteDocuments").asLong() > 0) result += " | " + word(locale, "Documentos con datos incompletos", "Documents with incomplete data", "数据不完整的单据") + ": " + response.path("incompleteDocuments").asLong();
        return result;
    }
    private static String totals(ExportRequest request, JsonNode response) {
        var totals = new ArrayList<String>();
        response.path("totals").forEach(total -> totals.add(total.path("currency").asText() + " | " + request.labels().totalQuantity() + ": "
                + formatted(SaasProductSalesHistoryService.decimal(total, "netQuantity"), "netQuantity", request.locale())
                + " | " + request.labels().totalAmount() + ": " + formatted(SaasProductSalesHistoryService.decimal(total, "netAmount"), "netAmount", request.locale())));
        return totals.isEmpty() ? word(request.locale(), "Sin movimientos para los filtros seleccionados", "No movements for the selected filters", "所选筛选条件下无记录") : String.join("\n", totals);
    }
    private static String formatted(BigDecimal value, String key, String locale) {
        var symbols = DecimalFormatSymbols.getInstance(Locale.forLanguageTag("zh".equals(locale) ? "zh-CN" : "en".equals(locale) ? "en-GB" : "es-ES"));
        return new DecimalFormat(Set.of("total", "netAmount").contains(key) ? "#,##0.00##############" : "#,##0.################", symbols).format(value);
    }
    private static int weight(String key) { return Set.of("customer", "document").contains(key) ? 120 : key.equals("currency") ? 45 : NUMERIC.contains(key) ? 65 : 90; }
    private static boolean comparison(ExportRequest request) { return "comparison".equals(request.view()); }
    private static String word(String locale, String es, String en, String zh) { return "zh".equals(locale) ? zh : "en".equals(locale) ? en : es; }
    private static void write(Cell cell, String value, CellStyle style) { cell.setCellValue(value); cell.setCellStyle(style); }
    private static void writeMerged(Sheet sheet, int index, int columns, String value, CellStyle style, float height) {
        var row = sheet.createRow(index); row.setHeightInPoints(height); write(row.createCell(0), value, style);
        sheet.addMergedRegion(new CellRangeAddress(index, index, 0, columns - 1));
    }
    private static void invalid() { throw new IllegalArgumentException("product_history_invalid_export"); }
}
