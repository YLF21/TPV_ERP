package com.tpverp.backend.catalog;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class ProductBulkXlsxService {

    private static final int ROW_WINDOW = 100;
    private static final String[] PRODUCT_HEADERS_ES = {
        "Codigo", "Codigo de barra", "Codigo barra 2", "Nombre", "Descripcion",
        "Precio compra", "Descuento compra", "Precio venta", "Precio socio",
        "Precio mayor", "Precio oferta", "Usar precio", "Descuento oferta",
        "Oferta desde", "Oferta hasta", "Referencia proveedor", "Comentarios",
        "Producto ID", "Version", "Imagen ID", "Almacen ID", "Tipo producto",
        "Tipo descuento", "Familia ID", "Familia", "Subfamilia ID", "Subfamilia",
        "Impuesto ID", "Impuesto", "Impuestos incluidos", "Oferta activa",
        "Almacen", "Cantidad", "Cantidad total", "Fila ID", "Seleccionado"
    };
    private static final String[] PRODUCT_HEADERS_EN = {
        "Code", "Barcode", "Barcode 2", "Name", "Description",
        "Purchase price", "Purchase discount", "Sale price", "Member price",
        "Wholesale price", "Offer price", "Price use", "Offer discount",
        "Offer from", "Offer until", "Supplier reference", "Comments",
        "Product ID", "Version", "Image ID", "Warehouse ID", "Product type",
        "Discount type", "Family ID", "Family", "Subfamily ID", "Subfamily",
        "Tax ID", "Tax", "Taxes included", "Offer active", "Warehouse",
        "Quantity", "Total quantity", "Row ID", "Selected"
    };
    private static final String[] SUPPLIER_HEADERS_ES = {
        "Fila ID", "Producto ID", "Proveedor ID", "Codigo proveedor", "Razon social",
        "Nombre comercial", "Documento", "Activo", "Referencia proveedor", "Principal",
        "Ultimo proveedor", "Precio compra bruto", "Descuento compra",
        "Precio compra neto", "Ultima entrada", "Pendiente"
    };
    private static final String[] SUPPLIER_HEADERS_EN = {
        "Row ID", "Product ID", "Supplier ID", "Supplier code", "Legal name",
        "Trade name", "Document", "Active", "Supplier reference", "Primary",
        "Last supplier", "Gross purchase price", "Purchase discount",
        "Net purchase price", "Last entry", "Pending"
    };

    public void export(ProductBulkXlsxContent request, OutputStream output) {
        SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_WINDOW);
        workbook.setCompressTempFiles(true);
        try (workbook) {
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle dateStyle = dateStyle(workbook);
            CellStyle instantStyle = instantStyle(workbook);
            boolean english = request.language() == ProductBulkXlsxContent.HeaderLanguage.EN;
            writeProducts(
                    workbook.createSheet(english ? "Products" : "Productos"),
                    request.content(), english ? PRODUCT_HEADERS_EN : PRODUCT_HEADERS_ES,
                    headerStyle, dateStyle);
            writeSuppliers(
                    workbook.createSheet(english ? "Suppliers" : "Proveedores"),
                    request.content(), english ? SUPPLIER_HEADERS_EN : SUPPLIER_HEADERS_ES,
                    headerStyle, instantStyle);
            workbook.write(output);
            output.flush();
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo exportar la edicion masiva a XLSX", exception);
        } finally {
            workbook.dispose();
        }
    }

    private static void writeProducts(
            Sheet sheet,
            List<ProductBulkEditContent.Row> content,
            String[] headers,
            CellStyle headerStyle,
            CellStyle dateStyle) {
        writeHeader(sheet, headers, headerStyle);
        int rowNumber = 1;
        for (ProductBulkEditContent.Row value : content) {
            ProductBulkEditContent.ProductData product = value.effectiveProduct();
            if (product == null) {
                continue;
            }
            Row row = sheet.createRow(rowNumber++);
            int column = 0;
            text(row, column++, product.code());
            text(row, column++, product.barcode());
            text(row, column++, product.barcode2());
            text(row, column++, product.name());
            text(row, column++, product.description());
            decimal(row, column++, product.purchasePrice(), "purchasePrice");
            decimal(row, column++, product.purchaseDiscountPercent(), "purchaseDiscountPercent");
            decimal(row, column++, product.salePrice(), "salePrice");
            decimal(row, column++, product.memberPrice(), "memberPrice");
            decimal(row, column++, product.wholesalePrice(), "wholesalePrice");
            decimal(row, column++, product.offerPrice(), "offerPrice");
            text(row, column++, product.discountType());
            decimal(row, column++, product.offerDiscountPercent(), "offerDiscountPercent");
            date(row, column++, product.offerFrom(), "offerFrom", dateStyle);
            date(row, column++, product.offerUntil(), "offerUntil", dateStyle);
            text(row, column++, supplierReference(value));
            text(row, column++, product.comments());
            uuid(row, column++, product.productId());
            number(row, column++, product.version());
            text(row, column++, product.imageId());
            text(row, column++, product.warehouseId());
            text(row, column++, product.productType());
            text(row, column++, product.backendDiscountType());
            text(row, column++, product.familyId());
            text(row, column++, product.familyName());
            text(row, column++, product.subfamilyId());
            text(row, column++, product.subfamilyName());
            text(row, column++, product.taxId());
            text(row, column++, product.taxName());
            bool(row, column++, product.taxesIncluded(), "taxesIncluded");
            bool(row, column++, product.offerActive(), "offerActive");
            text(row, column++, product.warehouseName());
            decimal(row, column++, product.quantity(), "quantity");
            decimal(row, column++, product.totalQuantity(), "totalQuantity");
            text(row, column++, value.id());
            bool(row, column, value.selected());
        }
        finish(sheet, headers.length, rowNumber);
    }

    private static void writeSuppliers(
            Sheet sheet,
            List<ProductBulkEditContent.Row> content,
            String[] headers,
            CellStyle headerStyle,
            CellStyle instantStyle) {
        writeHeader(sheet, headers, headerStyle);
        int rowNumber = 1;
        for (ProductBulkEditContent.Row value : content) {
            UUID productId = value.effectiveProduct() == null
                    ? null : value.effectiveProduct().productId();
            for (ProductBulkEditContent.SupplierData supplier : value.suppliers()) {
                writeSupplier(sheet.createRow(rowNumber++), value.id(), productId,
                        supplier, false, instantStyle);
            }
            if (value.pendingSupplier() != null) {
                writeSupplier(sheet.createRow(rowNumber++), value.id(), productId,
                        value.pendingSupplier(), true, instantStyle);
            }
        }
        finish(sheet, headers.length, rowNumber);
    }

    private static void writeSupplier(
            Row row, String rowId, UUID productId,
            ProductBulkEditContent.SupplierData supplier,
            boolean pending, CellStyle instantStyle) {
        int column = 0;
        text(row, column++, rowId);
        uuid(row, column++, productId);
        uuid(row, column++, supplier.id());
        text(row, column++, supplier.supplierCode());
        text(row, column++, supplier.legalName());
        text(row, column++, supplier.tradeName());
        text(row, column++, supplier.documentNumber());
        bool(row, column++, supplier.active());
        text(row, column++, supplier.supplierReference());
        nullableBool(row, column++, supplier.principal());
        nullableBool(row, column++, supplier.lastSupplier());
        decimal(row, column++, supplier.grossPurchasePrice(), "grossPurchasePrice");
        decimal(row, column++, supplier.purchaseDiscount(), "purchaseDiscount");
        decimal(row, column++, supplier.netPurchasePrice(), "netPurchasePrice");
        if (supplier.lastEntryAt() != null) {
            Cell cell = row.createCell(column);
            cell.setCellValue(Date.from(supplier.lastEntryAt()));
            cell.setCellStyle(instantStyle);
        }
        bool(row, column + 1, pending);
    }

    private static String supplierReference(ProductBulkEditContent.Row row) {
        if (row.pendingSupplier() != null && !blank(row.pendingSupplier().supplierReference())) {
            return row.pendingSupplier().supplierReference();
        }
        return row.suppliers().stream()
                .filter(supplier -> Boolean.TRUE.equals(supplier.principal()))
                .findFirst()
                .or(() -> row.suppliers().stream().findFirst())
                .map(ProductBulkEditContent.SupplierData::supplierReference)
                .orElse(null);
    }

    private static void writeHeader(Sheet sheet, String[] headers, CellStyle style) {
        Row row = sheet.createRow(0);
        for (int index = 0; index < headers.length; index++) {
            Cell cell = row.createCell(index);
            cell.setCellValue(headers[index]);
            cell.setCellStyle(style);
        }
        sheet.createFreezePane(0, 1);
    }

    private static void finish(Sheet sheet, int columns, int rows) {
        if (rows > 1) {
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                    0, rows - 1, 0, columns - 1));
        }
        for (int index = 0; index < columns; index++) {
            int width = switch (index) {
                case 3, 24, 26, 28 -> 24;
                case 4, 16 -> 36;
                default -> 16;
            };
            sheet.setColumnWidth(index, width * 256);
        }
    }

    private static CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        var font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private static CellStyle dateStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
        return style;
    }

    private static CellStyle instantStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
        return style;
    }

    private static void text(Row row, int column, String value) {
        if (!blank(value)) {
            row.createCell(column).setCellValue(value.trim());
        }
    }

    private static void uuid(Row row, int column, UUID value) {
        if (value != null) {
            row.createCell(column).setCellValue(value.toString());
        }
    }

    private static void number(Row row, int column, Long value) {
        if (value != null) {
            row.createCell(column).setCellValue(value.doubleValue());
        }
    }

    private static void decimal(Row row, int column, String value, String field) {
        if (blank(value)) {
            return;
        }
        try {
            row.createCell(column).setCellValue(
                    new BigDecimal(value.trim().replace(',', '.')).doubleValue());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " no es un numero valido");
        }
    }

    private static void bool(Row row, int column, boolean value) {
        row.createCell(column).setCellValue(value);
    }

    private static void nullableBool(Row row, int column, Boolean value) {
        if (value != null) {
            bool(row, column, value);
        }
    }

    private static void bool(Row row, int column, String value, String field) {
        if (blank(value)) {
            return;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (List.of("true", "yes", "si", "1", "common.yes").contains(normalized)) {
            bool(row, column, true);
        } else if (List.of("false", "no", "0", "common.no").contains(normalized)) {
            bool(row, column, false);
        } else {
            throw new IllegalArgumentException(field + " no es un booleano valido");
        }
    }

    private static void date(
            Row row, int column, String value, String field, CellStyle style) {
        if (blank(value)) {
            return;
        }
        try {
            LocalDate date = LocalDate.parse(value.trim());
            Cell cell = row.createCell(column);
            cell.setCellValue(Date.from(date.atStartOfDay().toInstant(ZoneOffset.UTC)));
            cell.setCellStyle(style);
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException(field + " debe usar el formato yyyy-MM-dd");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank() || "-".equals(value.trim());
    }
}
