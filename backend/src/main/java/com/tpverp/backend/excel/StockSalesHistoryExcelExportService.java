package com.tpverp.backend.excel;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.ProductType;
import com.tpverp.backend.document.DocumentStatus;
import com.tpverp.backend.inventory.StockSalesHistoryRow;
import com.tpverp.backend.inventory.StockSalesHistoryService;
import com.tpverp.backend.organization.CurrentOrganization;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.AttributedString;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.SheetUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockSalesHistoryExcelExportService {

    private static final Map<String, Function<StockSalesHistoryRow, Object>> VALUES = Map.ofEntries(
            Map.entry("occurredAt", StockSalesHistoryRow::occurredAt),
            Map.entry("document", row -> documentLabel(row)),
            Map.entry("status", row -> row.status().name()),
            Map.entry("customer", row -> first(row.customerName(), row.customerId())),
            Map.entry("quantity", StockSalesHistoryRow::quantity),
            Map.entry("unitPrice", StockSalesHistoryRow::unitPrice),
            Map.entry("discount", StockSalesHistoryRow::discountPercent),
            Map.entry("total", StockSalesHistoryRow::lineTotal),
            Map.entry("user", row -> first(row.userName(), row.userId())),
            Map.entry("store", row -> first(row.storeName(), row.storeId())),
            Map.entry("warehouse", row -> first(row.warehouseName(), row.warehouseId())));

    private final CurrentOrganization organization;
    private final ProductRepository products;
    private final StockSalesHistoryService historyService;

    public StockSalesHistoryExcelExportService(
            CurrentOrganization organization,
            ProductRepository products,
            StockSalesHistoryService historyService) {
        this.organization = organization;
        this.products = products;
        this.historyService = historyService;
    }

    @Transactional(readOnly = true)
    public byte[] export(UUID productId, StockSalesHistoryExportRequest request) {
        var product = product(productId);
        var rows = historyService.history(productId, request.from(), request.to()).stream()
                .filter(row -> request.status() == null || row.status() == request.status())
                .toList();
        var columns = validatedColumns(request.columns());
        var totals = totals(rows);
        return workbook(product, request, columns, rows, totals);
    }

    private Product product(UUID productId) {
        return products.findAllByStoreIdAndIdIn(
                        organization.currentStore().getId(), List.of(productId)).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Producto no encontrado"));
    }

    private static List<StockSalesHistoryExportRequest.Column> validatedColumns(
            List<StockSalesHistoryExportRequest.Column> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("columnas es obligatorio");
        }
        columns.forEach(column -> {
            if (!VALUES.containsKey(column.key())) {
                throw new IllegalArgumentException("Columna no permitida: " + column.key());
            }
        });
        return List.copyOf(columns);
    }

    private static byte[] workbook(
            Product product,
            StockSalesHistoryExportRequest request,
            List<StockSalesHistoryExportRequest.Column> columns,
            List<StockSalesHistoryRow> rows,
            Totals totals) {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet(safeSheetName(request.labels().title()));
            sheet.setDisplayGridlines(false);
            var styles = Styles.create(workbook);
            int layoutColumns = Math.max(2, columns.size());
            int rowIndex = 0;

            var title = sheet.createRow(rowIndex++);
            title.setHeightInPoints(26);
            writeText(title.createCell(0), request.labels().title(), styles.title());
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, layoutColumns - 1));

            writePair(sheet.createRow(rowIndex++), request.labels().product(), product.getName(), styles);
            writePair(sheet.createRow(rowIndex++), request.labels().code(), productCode(product), styles);
            writePair(sheet.createRow(rowIndex++), request.labels().period(), period(request), styles);
            writePair(sheet.createRow(rowIndex++), request.labels().status(),
                    request.status() == null ? request.labels().allStatuses() : request.status().name(), styles);
            if (layoutColumns > 2) {
                for (int metadataRow = 1; metadataRow <= 4; metadataRow++) {
                    sheet.addMergedRegion(new CellRangeAddress(metadataRow, metadataRow, 1, layoutColumns - 1));
                }
            }
            rowIndex++;

            int headerIndex = rowIndex;
            var header = sheet.createRow(rowIndex++);
            for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
                var column = columns.get(columnIndex);
                writeText(header.createCell(columnIndex), column.label(),
                        numericColumn(column.key()) ? styles.numericHeader() : styles.header());
            }

            for (var historyRow : rows) {
                var excelRow = sheet.createRow(rowIndex++);
                for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
                    var column = columns.get(columnIndex);
                    writeValue(excelRow.createCell(columnIndex), VALUES.get(column.key()).apply(historyRow),
                            column.key(), product.getProductType(), styles);
                }
            }

            int lastDataRow = Math.max(headerIndex, rowIndex - 1);
            sheet.setAutoFilter(new CellRangeAddress(headerIndex, lastDataRow, 0, columns.size() - 1));
            sheet.createFreezePane(0, headerIndex + 1);
            rowIndex++;
            writeTotal(sheet.createRow(rowIndex++), request.labels().totalQuantity(), totals.quantity(),
                    quantityStyle(product.getProductType(), styles), styles);
            writeTotal(sheet.createRow(rowIndex), request.labels().totalAmount(), totals.amount(), styles.currency(), styles);

            for (int columnIndex = 0; columnIndex < layoutColumns; columnIndex++) {
                String key = columnIndex < columns.size() ? columns.get(columnIndex).key() : "";
                int minimum = "occurredAt".equals(key) ? 22 : numericColumn(key) ? 16 : 20;
                double measured = SheetUtil.getColumnWidth(sheet, columnIndex, false, headerIndex, sheet.getLastRowNum());
                sheet.setColumnWidth(columnIndex, (int) Math.ceil(Math.min(48, Math.max(minimum, measured + 3)) * 256));
            }
            fitTextRows(sheet, layoutColumns, headerIndex);

            var output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo exportar el historial de ventas", exception);
        }
    }

    private static boolean numericColumn(String key) {
        return "quantity".equals(key) || "unitPrice".equals(key) || "discount".equals(key) || "total".equals(key);
    }

    private static void fitTextRows(Sheet sheet, int layoutColumns, int headerIndex) {
        var fonts = new HashMap<Integer, java.awt.Font>();
        var context = new FontRenderContext(null, true, true);
        float defaultCharWidth = SheetUtil.getDefaultCharWidthAsFloat(sheet.getWorkbook());
        for (Row row : sheet) {
            float height = row.getRowNum() == 0 || row.getRowNum() == headerIndex ? 28 : 20;
            for (Cell cell : row) {
                if (cell.getCellType() != org.apache.poi.ss.usermodel.CellType.STRING) continue;
                int lastColumn = row.getRowNum() == 0 || (row.getRowNum() <= 4 && cell.getColumnIndex() == 1)
                        ? layoutColumns - 1 : cell.getColumnIndex();
                float width = 0;
                for (int column = cell.getColumnIndex(); column <= lastColumn; column++) {
                    width += sheet.getColumnWidth(column) / 256f * defaultCharWidth;
                }
                var font = fonts.computeIfAbsent(cell.getCellStyle().getFontIndex(), index -> {
                    var source = sheet.getWorkbook().getFontAt(index);
                    return new java.awt.Font(source.getFontName(), source.getBold() ? java.awt.Font.BOLD : java.awt.Font.PLAIN,
                            source.getFontHeightInPoints());
                });
                float textHeight = 8;
                for (String line : cell.getStringCellValue().split("\\r\\n|\\r|\\n", -1)) {
                    if (line.isEmpty()) {
                        textHeight += font.getSize2D() * 1.25f;
                        continue;
                    }
                    var text = new AttributedString(line);
                    text.addAttribute(TextAttribute.FONT, font);
                    var measurer = new LineBreakMeasurer(text.getIterator(), context);
                    while (measurer.getPosition() < line.length()) {
                        var layout = measurer.nextLayout(Math.max(1, width - 10));
                        textHeight += layout.getAscent() + layout.getDescent() + layout.getLeading();
                    }
                }
                height = Math.max(height, textHeight);
            }
            row.setHeightInPoints(Math.min(409.5f, height));
        }
    }

    private static void writePair(Row row, String label, String value, Styles styles) {
        writeText(row.createCell(0), label, styles.metaLabel());
        writeText(row.createCell(1), value, styles.metaValue());
    }

    private static void writeTotal(Row row, String label, BigDecimal value, CellStyle valueStyle, Styles styles) {
        writeText(row.createCell(0), label, styles.totalLabel());
        var valueCell = row.createCell(1);
        valueCell.setCellValue(value.doubleValue());
        valueCell.setCellStyle(valueStyle);
    }

    private static void writeValue(
            Cell cell,
            Object value,
            String key,
            ProductType productType,
            Styles styles) {
        if (value == null) {
            cell.setBlank();
            cell.setCellStyle(styles.body());
            return;
        }
        if (value instanceof java.time.Instant instant) {
            cell.setCellValue(Date.from(instant));
            cell.setCellStyle(styles.date());
            return;
        }
        if (value instanceof BigDecimal number) {
            cell.setCellValue(number.doubleValue());
            cell.setCellStyle("discount".equals(key) ? styles.percentNumber() :
                    "quantity".equals(key) ? quantityStyle(productType, styles) : styles.currency());
            return;
        }
        writeText(cell, value.toString(), styles.body());
    }

    private static CellStyle quantityStyle(ProductType productType, Styles styles) {
        return productType == ProductType.UNIT
                ? styles.integerQuantity()
                : styles.decimalQuantity();
    }

    private static void writeText(Cell cell, String value, CellStyle style) {
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    static Totals totals(List<StockSalesHistoryRow> rows) {
        var effective = rows.stream().filter(row -> row.status() != DocumentStatus.ANULADO).toList();
        return new Totals(
                effective.stream().map(StockSalesHistoryRow::quantity)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                effective.stream().map(StockSalesHistoryRow::lineTotal)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static String productCode(Product product) {
        return first(product.getCode(), product.getBarcode(), product.getBarcode2(), product.getId());
    }

    private static String period(StockSalesHistoryExportRequest request) {
        if (request.from() == null && request.to() == null) return "";
        if (request.from() == null) return request.to().toString();
        if (request.to() == null || request.from().equals(request.to())) return request.from().toString();
        return request.from() + " - " + request.to();
    }

    private static String documentLabel(StockSalesHistoryRow row) {
        var identity = row.documentNumber() == null || row.documentNumber().isBlank()
                ? row.documentId().toString() : row.documentNumber();
        return row.documentType().name() + " " + identity;
    }

    private static String first(Object... values) {
        for (var value : values) {
            if (value != null && !value.toString().isBlank()) return value.toString();
        }
        return "";
    }

    private static String safeSheetName(String value) {
        var sanitized = value.replaceAll("[\\\\/?*\\[\\]:]", "-");
        return sanitized.substring(0, Math.min(31, sanitized.length()));
    }

    record Totals(BigDecimal quantity, BigDecimal amount) {
    }

    private record Styles(
            CellStyle title,
            CellStyle header,
            CellStyle numericHeader,
            CellStyle metaLabel,
            CellStyle metaValue,
            CellStyle body,
            CellStyle date,
            CellStyle integerQuantity,
            CellStyle decimalQuantity,
            CellStyle currency,
            CellStyle percentNumber,
            CellStyle totalLabel) {

        static Styles create(Workbook workbook) {
            var body = workbook.createCellStyle();
            body.setFont(font(workbook, false, (short) 10));
            body.setAlignment(HorizontalAlignment.LEFT);
            body.setVerticalAlignment(VerticalAlignment.CENTER);
            body.setWrapText(true);

            var title = workbook.createCellStyle();
            title.cloneStyleFrom(body);
            title.setFont(font(workbook, true, (short) 15));

            var header = workbook.createCellStyle();
            header.cloneStyleFrom(body);
            header.setFont(font(workbook, true, (short) 10));
            header.setBorderBottom(BorderStyle.THIN);
            var numericHeader = workbook.createCellStyle();
            numericHeader.cloneStyleFrom(header);
            numericHeader.setAlignment(HorizontalAlignment.RIGHT);

            var metaLabel = workbook.createCellStyle();
            metaLabel.cloneStyleFrom(body);
            metaLabel.setFont(font(workbook, true, (short) 10));
            var metaValue = workbook.createCellStyle();
            metaValue.cloneStyleFrom(body);

            var date = workbook.createCellStyle();
            date.cloneStyleFrom(body);
            date.setWrapText(false);
            date.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy hh:mm"));

            var integerQuantity = workbook.createCellStyle();
            integerQuantity.cloneStyleFrom(body);
            integerQuantity.setAlignment(HorizontalAlignment.RIGHT);
            integerQuantity.setWrapText(false);
            integerQuantity.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));

            var decimalQuantity = workbook.createCellStyle();
            decimalQuantity.cloneStyleFrom(integerQuantity);
            decimalQuantity.setDataFormat(workbook.createDataFormat().getFormat("#,##0.###"));

            var currency = workbook.createCellStyle();
            currency.cloneStyleFrom(integerQuantity);
            currency.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00 [$€-x-euro2]"));

            var percent = workbook.createCellStyle();
            percent.cloneStyleFrom(integerQuantity);
            percent.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00\"%\""));

            var totalLabel = workbook.createCellStyle();
            totalLabel.cloneStyleFrom(body);
            totalLabel.setFont(font(workbook, true, (short) 11));

            return new Styles(title, header, numericHeader, metaLabel, metaValue, body, date,
                    integerQuantity, decimalQuantity, currency, percent, totalLabel);
        }

        private static Font font(Workbook workbook, boolean bold, short size) {
            var font = workbook.createFont();
            font.setFontName("Arial");
            font.setBold(bold);
            font.setColor(IndexedColors.BLACK.getIndex());
            font.setFontHeightInPoints(size);
            return font;
        }
    }
}
