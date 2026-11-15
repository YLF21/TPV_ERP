package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.document.Money;
import com.tpverp.backend.organization.CurrentOrganization;
import jakarta.validation.Validator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehouseTransferExportService {
    private final WarehouseRepository warehouses;
    private final ProductRepository products;
    private final CurrentOrganization organization;
    private final Validator validator;

    public WarehouseTransferExportService(WarehouseRepository warehouses, ProductRepository products,
                                         CurrentOrganization organization, Validator validator) {
        this.warehouses = warehouses;
        this.products = products;
        this.organization = organization;
        this.validator = validator;
    }

    @Transactional(readOnly = true)
    public byte[] export(WarehouseTransferExportController.Request request) {
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new IllegalArgumentException("Los datos del traspaso no son válidos");
        }
        if (request.sourceWarehouseId().equals(request.targetWarehouseId())) {
            throw new IllegalArgumentException("Los almacenes de origen y destino deben ser distintos");
        }
        var store = organization.currentStore();
        var warehouseMap = warehouses.findByStoreIdAndIdIn(store.getId(),
                        List.of(request.sourceWarehouseId(), request.targetWarehouseId())).stream()
                .collect(Collectors.toMap(Warehouse::getId, Function.identity()));
        var source = warehouseMap.get(request.sourceWarehouseId());
        var target = warehouseMap.get(request.targetWarehouseId());
        if (source == null || target == null) {
            throw new IllegalArgumentException("Almacén no encontrado en la tienda actual");
        }
        var productMap = products.findAllByStoreIdAndIdIn(store.getId(),
                        request.lines().stream().map(WarehouseTransferExportController.Line::productId).distinct().toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        var priceSource = Objects.requireNonNullElse(request.priceSource(), WarehouseInputPriceSource.PURCHASE);
        var lines = request.lines().stream().map(line -> {
            var product = productMap.get(line.productId());
            if (product == null) throw new IllegalArgumentException("Producto no encontrado en la tienda actual");
            var name = line.productName() == null || line.productName().isBlank() ? product.getName() : line.productName();
            var price = line.unitPrice() == null ? priceSource.price(product) : line.unitPrice();
            // Reuse the input document's rounding order: gross line to cents, then line discount to cents.
            var values = new WarehouseInputLine(UUID.randomUUID(), line.productId(), line.quantity(),
                    price, line.discount(), line.priceOverridden(), name);
            return new ExportLine(product.getCode(), product.getBarcode(), values);
        }).toList();
        var subtotal = Money.euros(lines.stream().map(line -> line.values().getTotal())
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        var discount = Objects.requireNonNullElse(request.globalDiscount(), BigDecimal.ZERO);
        var total = Money.euros(subtotal.multiply(BigDecimal.ONE.subtract(discount.movePointLeft(2))));
        var labels = new Labels(request.locale() == null ? store.getLocale() : request.locale());
        var company = organization.currentCompany();

        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = book.createSheet(labels.text("Traspaso", "Transfer", "调拨"));
            var metadata = new java.util.ArrayList<>(WarehouseExportLayout.organizationMetadata(store, company,
                    request.locale() == null ? store.getLocale() : request.locale(), java.time.Instant.now()));
            metadata.add(new WarehouseExportLayout.MetadataField(labels.text("Fecha", "Date", "日期"), request.date().toString()));
            metadata.add(new WarehouseExportLayout.MetadataField(labels.text("Número", "Number", "编号"), request.number()));
            metadata.add(new WarehouseExportLayout.MetadataField(labels.text("Estado", "Status", "状态"), labels.status(request.status())));
            metadata.add(new WarehouseExportLayout.MetadataField(labels.text("Origen", "Source", "调出仓库"), source.getName()));
            metadata.add(new WarehouseExportLayout.MetadataField(labels.text("Destino", "Destination", "调入仓库"), target.getName()));
            metadata.add(new WarehouseExportLayout.MetadataField(labels.text("Número externo", "External number", "外部编号"), request.externalNumber()));
            metadata.add(new WarehouseExportLayout.MetadataField(labels.text("Observaciones", "Notes", "备注"), request.notes()));
            metadata.add(new WarehouseExportLayout.MetadataField(labels.text("Fuente de precio", "Price source", "价格来源"), labels.priceSource(priceSource)));
            var headings = List.of(labels.text("Código", "Code", "编码"), labels.text("Código de barras", "Barcode", "条码"),
                    labels.text("Artículo", "Product", "商品"), labels.text("Descuento", "Discount", "折扣"),
                    labels.text("Precio unitario", "Unit price", "单价"), labels.text("Cantidad", "Quantity", "数量"),
                    labels.text("Total", "Total", "合计"));
            var layout = WarehouseExportLayout.workbook(book, sheet,
                    labels.text("TRASPASO DE ALMACÉN", "WAREHOUSE TRANSFER", "仓库调拨"),
                    metadata, headings, new int[] {20, 24, 48, 16, 20, 18, 22},
                    request.locale() == null ? store.getLocale() : request.locale());
            int rowNumber = layout.headerRow() + 1;
            for (var line : lines) {
                var row = sheet.createRow(rowNumber++);
                boolean stripe = (rowNumber - layout.headerRow()) % 2 == 0;
                cell(row, 0, line.code(), layout.text(stripe));
                cell(row, 1, line.barcode(), layout.text(stripe));
                cell(row, 2, line.values().getProductName(), layout.text(stripe));
                number(row, 3, line.values().getDiscount(), layout.percent(stripe));
                number(row, 4, line.values().getPurchaseUnitPrice(), layout.money3(stripe));
                number(row, 5, line.values().getQuantity(), layout.number(stripe));
                number(row, 6, line.values().getTotal(), layout.money(stripe));
                WarehouseExportLayout.bodyRow(row, layout, rowNumber - layout.headerRow() - 2);
            }
            WarehouseExportLayout.autoFilter(sheet, layout.headerRow(), rowNumber - 1, 7);
            summary(sheet, ++rowNumber, labels.text("Subtotal", "Subtotal", "小计"), subtotal, layout.totalLabel(), layout.totalMoney());
            summary(sheet, ++rowNumber, labels.text("Descuento global", "Global discount", "整单折扣"), discount, layout.totalLabel(), layout.totalPercent());
            summary(sheet, ++rowNumber, labels.text("Importe descuento", "Discount amount", "折扣金额"), subtotal.subtract(total), layout.totalLabel(), layout.totalMoney());
            summary(sheet, ++rowNumber, labels.text("Total", "Total", "合计"), total, layout.totalLabel(), layout.totalMoney());
            book.write(output);
            return output.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("No se pudo generar el Excel del traspaso", error);
        }
    }

    private static void cell(Row row, int column, String value, CellStyle style) {
        var cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private static void number(Row row, int column, BigDecimal value, CellStyle style) {
        var cell = row.createCell(column);
        cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
    }

    private static void summary(Sheet sheet, int rowNumber, String label, BigDecimal value,
                                CellStyle labelStyle, CellStyle valueStyle) {
        var row = sheet.createRow(rowNumber);
        row.setHeightInPoints(30);
        cell(row, 4, label, labelStyle);
        cell(row, 5, "", labelStyle);
        sheet.addMergedRegion(new CellRangeAddress(rowNumber, rowNumber, 4, 5));
        number(row, 6, value, valueStyle);
    }

    private record ExportLine(String code, String barcode, WarehouseInputLine values) {}

    private record Labels(String locale) {
        String text(String es, String en, String zh) {
            var language = locale == null ? "es" : locale.toLowerCase(Locale.ROOT);
            return language.startsWith("zh") ? zh : language.startsWith("en") ? en : es;
        }

        String status(String status) {
            return switch (Objects.requireNonNullElse(status, "DRAFT")) {
                case "CONFIRMED" -> text("Confirmado", "Confirmed", "已确认");
                case "CANCELLED" -> text("Cancelado", "Cancelled", "已取消");
                default -> text("Borrador", "Draft", "草稿");
            };
        }

        String priceSource(WarehouseInputPriceSource source) {
            return switch (source) {
                case PURCHASE -> text("Compra", "Purchase", "进货价");
                case SALE -> text("Venta", "Sale", "零售价");
                case MEMBER -> text("Socio", "Member", "会员价");
                case WHOLESALE -> text("Mayorista", "Wholesale", "批发价");
                case OFFER -> text("Oferta", "Offer", "特价");
            };
        }
    }
}
