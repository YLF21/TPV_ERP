package com.tpverp.backend.inventory;

import com.tpverp.backend.organization.CurrentOrganization;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class ReplenishmentExportService {
    private final CurrentOrganization organization;

    @Autowired
    public ReplenishmentExportService(CurrentOrganization organization) { this.organization = organization; }

    public ReplenishmentExportService() { this.organization = null; }
    private static final int MAX_ROWS = 50_000;
    private static final float[] WIDTHS = {86, 65, 84, 129, 52, 119, 54, 63, 84, 74, 76, 68, 73};
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
            int[] widths = {18, 17, 23, 43, 16, 36, 16, 19, 26, 22, 22, 18, 19};
            var layout = WarehouseExportLayout.workbook(book, sheet, copy.title(),
                    metadata(copy.locale()), copy.headers(), widths, copy.locale());
            for (int r = 0; r < items.size(); r++) {
                var row = sheet.createRow(layout.headerRow() + 1 + r);
                var values = values(copy, items.get(r));
                for (int c = 0; c < values.size(); c++) {
                    var cell = row.createCell(c);
                    if (numeric(c) && !values.get(c).isEmpty()) {
                        cell.setCellValue(new BigDecimal(values.get(c)).doubleValue());
                        cell.setCellStyle(c == 7 ? layout.money3(r % 2 == 1) : layout.number(r % 2 == 1));
                    } else {
                        // POI stores all untrusted text (including =, +, - and @ prefixes) as literal strings.
                        cell.setCellType(CellType.STRING); cell.setCellValue(values.get(c));
                        cell.setCellStyle(layout.text(r % 2 == 1));
                    }
                }
                WarehouseExportLayout.bodyRow(row, layout, r);
            }
            WarehouseExportLayout.autoFilter(sheet, layout.headerRow(), layout.headerRow() + items.size(), WIDTHS.length);
            book.write(output); return output.toByteArray();
        }
    }

    private byte[] pdf(Copy copy, List<ReplenishmentView> items) throws IOException {
        var numbers = java.text.NumberFormat.getNumberInstance(copy.numberLocale());
        numbers.setMaximumFractionDigits(3);
        var rows = items.stream().map(item -> {
            var values = values(copy, item);
            var formatted = new ArrayList<String>();
            for (int c = 0; c < values.size(); c++) formatted.add(numeric(c) && !values.get(c).isEmpty()
                    ? numbers.format(new BigDecimal(values.get(c))) : values.get(c));
            return new WarehouseExportLayout.PdfRow(formatted);
        }).toList();
        return WarehouseExportLayout.pdf(new WarehouseExportLayout.PdfSpec(copy.title(), copy.locale(),
                metadata(copy.locale()), copy.headers(), WIDTHS, rows));
    }

    private List<WarehouseExportLayout.MetadataField> metadata(String locale) {
        if (organization == null) return List.of();
        return WarehouseExportLayout.organizationMetadata(organization.currentStore(),
                organization.currentCompany(), locale, Instant.now());
    }

    private static boolean numeric(int column) { return column == 4 || column == 6 || column == 7; }
    private static String value(Object object) { return Objects.toString(object, ""); }
    private static String decimal(BigDecimal number) { return number == null ? "" : number.toPlainString(); }
    private static List<String> values(Copy copy, ReplenishmentView item) {
        return List.of(value(item.warehouseName()), value(item.code()), value(item.barcode()), value(item.name()),
                decimal(item.quantity()), value(item.reason()), decimal(item.stock()), decimal(item.purchasePrice()),
                value(item.supplierName()), value(item.familyName()), value(item.subfamilyName()),
                copy.status(item.status()), item.orderDate() == null ? "" : DATE.format(item.orderDate()));
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
