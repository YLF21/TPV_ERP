package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class StockCountExportService {
    private final StockCountService counts;
    private final CurrentOrganization organization;
    private final WarehouseRepository warehouses;

    @Autowired
    public StockCountExportService(StockCountService counts, CurrentOrganization organization,
            WarehouseRepository warehouses) {
        this.counts = counts;
        this.organization = organization;
        this.warehouses = warehouses;
    }

    public StockCountExportService(StockCountService counts) { this(counts, null, null); }

    public byte[] excel(UUID id) {
        var count = counts.get(id);
        String locale = locale();
        var headers = headers(locale);
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Inventario");
            var layout = WarehouseExportLayout.workbook(workbook, sheet,
                    WarehouseExportLayout.tr(locale, "INVENTARIO", "STOCK COUNT", "库存盘点"),
                    metadata(count, locale), headers, new int[] {18, 24, 48, 17, 17, 17}, locale);
            CellStyle less = accent(workbook, layout.number(false), (byte) 204, (byte) 41, (byte) 41);
            CellStyle more = accent(workbook, layout.number(false), (byte) 140, (byte) 102, (byte) 0);
            CellStyle equal = accent(workbook, layout.number(false), (byte) 25, (byte) 115, (byte) 51);
            CellStyle lessStripe = accent(workbook, layout.number(true), (byte) 204, (byte) 41, (byte) 41);
            CellStyle moreStripe = accent(workbook, layout.number(true), (byte) 140, (byte) 102, (byte) 0);
            CellStyle equalStripe = accent(workbook, layout.number(true), (byte) 25, (byte) 115, (byte) 51);
            int rowNumber = layout.headerRow() + 1;
            for (var line : count.lines()) {
                var row = sheet.createRow(rowNumber++);
                boolean stripe = (rowNumber - layout.headerRow()) % 2 == 0;
                for (int column = 0; column < 3; column++) {
                    String value = switch (column) {
                        case 0 -> line.productCode();
                        case 1 -> line.productBarcode();
                        default -> line.productName();
                    };
                    var cell = row.createCell(column);
                    cell.setCellValue(safe(value));
                    cell.setCellStyle(layout.text(stripe));
                }
                numeric(row, 3, line.expectedQuantity(), layout.number(stripe));
                numeric(row, 4, line.countedQuantity(), layout.number(stripe));
                numeric(row, 5, line.difference(), line.difference() == null ? layout.number(stripe)
                        : line.difference().signum() < 0 ? (stripe ? lessStripe : less)
                        : line.difference().signum() > 0 ? (stripe ? moreStripe : more)
                        : (stripe ? equalStripe : equal));
                WarehouseExportLayout.bodyRow(row, layout, rowNumber - layout.headerRow() - 2);
            }
            WarehouseExportLayout.autoFilter(sheet, layout.headerRow(), rowNumber - 1, headers.size());
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) { throw new IllegalStateException("No se pudo exportar el inventario", exception); }
    }

    public byte[] pdf(UUID id) {
        var count = counts.get(id);
        String locale = locale();
        var rows = count.lines().stream().map(line -> new WarehouseExportLayout.PdfRow(List.of(
                safe(line.productCode()), safe(line.productBarcode()), safe(line.productName()),
                number(line.expectedQuantity()), number(line.countedQuantity()), number(line.difference())),
                5, line.difference() == null ? 0 : line.difference().signum())).toList();
        try {
            return WarehouseExportLayout.pdf(new WarehouseExportLayout.PdfSpec(
                    WarehouseExportLayout.tr(locale, "INVENTARIO", "STOCK COUNT", "库存盘点"),
                    locale, metadata(count, locale), headers(locale),
                    new float[] {100, 110, 240, 75, 75, 75}, rows));
        } catch (IOException exception) { throw new IllegalStateException("No se pudo exportar el inventario", exception); }
    }

    private List<WarehouseExportLayout.MetadataField> metadata(StockCountView count, String locale) {
        var values = new ArrayList<WarehouseExportLayout.MetadataField>();
        if (organization != null) {
            values.addAll(WarehouseExportLayout.organizationMetadata(organization.currentStore(),
                    organization.currentCompany(), locale, count.createdAt()));
            if (warehouses != null) warehouses.findById(count.warehouseId()).ifPresent(warehouse ->
                    values.add(new WarehouseExportLayout.MetadataField(
                            WarehouseExportLayout.tr(locale, "Almacén", "Warehouse", "仓库"), warehouse.getName())));
        }
        values.add(new WarehouseExportLayout.MetadataField(
                WarehouseExportLayout.tr(locale, "Documento", "Document", "单据"), count.number()));
        values.add(new WarehouseExportLayout.MetadataField(
                WarehouseExportLayout.tr(locale, "Estado", "Status", "状态"), Objects.toString(count.status(), "")));
        if (count.documentDate() != null) values.add(new WarehouseExportLayout.MetadataField(
                WarehouseExportLayout.tr(locale, "Fecha", "Date", "日期"), count.documentDate().toString()));
        if (count.notes() != null && !count.notes().isBlank()) values.add(new WarehouseExportLayout.MetadataField(
                WarehouseExportLayout.tr(locale, "Notas", "Notes", "备注"), count.notes()));
        return values;
    }

    private String locale() { return organization == null ? "es" : organization.currentStore().getLocale(); }

    private static List<String> headers(String locale) {
        return List.of(WarehouseExportLayout.tr(locale, "Código", "Code", "编码"),
                WarehouseExportLayout.tr(locale, "Código de barras", "Barcode", "条码"),
                WarehouseExportLayout.tr(locale, "Producto", "Product", "商品"),
                WarehouseExportLayout.tr(locale, "Antes", "Before", "盘点前"),
                WarehouseExportLayout.tr(locale, "Después", "After", "盘点后"),
                WarehouseExportLayout.tr(locale, "Diferencia", "Difference", "差异"));
    }

    private static void numeric(org.apache.poi.ss.usermodel.Row row, int column, BigDecimal value, CellStyle style) {
        var cell = row.createCell(column);
        if (value != null) cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
    }

    private static CellStyle accent(XSSFWorkbook book, CellStyle base, byte red, byte green, byte blue) {
        var style = book.createCellStyle();
        style.cloneStyleFrom(base);
        var font = book.createFont();
        font.setFontName("Calibri");
        font.setFontHeightInPoints((short) 10);
        font.setColor(new org.apache.poi.xssf.usermodel.XSSFColor(new byte[] {red, green, blue}, null));
        style.setFont(font);
        return style;
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static String number(BigDecimal value) { return value == null ? "" : value.stripTrailingZeros().toPlainString(); }
}
