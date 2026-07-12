package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

class ProductBulkXlsxServiceTest {

    private final ProductBulkXlsxService service = new ProductBulkXlsxService();

    @Test
    void exportsRealTypedXlsxWithEveryEditableProductColumn() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        ProductBulkEditContent.SupplierData supplier = new ProductBulkEditContent.SupplierData(
                supplierId,
                "00000001",
                "Proveedor SL",
                "Proveedor",
                "B12345678",
                true,
                "REF-1",
                true,
                false,
                "9.00",
                "10.00",
                "8.10",
                Instant.parse("2026-07-10T12:30:00Z"));
        ProductBulkEditContent.Row row = row(productId, supplier);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.export(new ProductBulkXlsxContent(List.of(row)), output);
        byte[] bytes = output.toByteArray();

        assertThat(bytes).startsWith((byte) 'P', (byte) 'K');
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            var products = workbook.getSheet("Productos");
            assertThat(products.getRow(0).getLastCellNum()).isEqualTo((short) 36);
            assertThat(products.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Codigo");
            assertThat(products.getRow(0).getCell(5).getStringCellValue()).isEqualTo("Precio compra");
            assertThat(products.getRow(0).getCell(11).getStringCellValue()).isEqualTo("Usar precio");
            assertThat(products.getRow(1).getCell(7).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(products.getRow(1).getCell(7).getNumericCellValue()).isEqualTo(12.50);
            assertThat(products.getRow(1).getCell(29).getCellType()).isEqualTo(CellType.BOOLEAN);
            assertThat(products.getRow(1).getCell(29).getBooleanCellValue()).isTrue();
            assertThat(DateUtil.isCellDateFormatted(products.getRow(1).getCell(13))).isTrue();

            var suppliers = workbook.getSheet("Proveedores");
            assertThat(suppliers.getRow(1).getCell(2).getStringCellValue())
                    .isEqualTo(supplierId.toString());
            assertThat(suppliers.getRow(1).getCell(11).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(suppliers.getRow(1).getCell(15).getBooleanCellValue()).isFalse();
        }
    }

    @Test
    void exportsEnglishRoundTripHeadersWithFixedWidths() throws Exception {
        ProductBulkEditContent.Row row = row(
                UUID.randomUUID(),
                new ProductBulkEditContent.SupplierData(
                        UUID.randomUUID(), null, null, null, null, true,
                        "REF-EN", true, false, null, null, null, null));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        service.export(new ProductBulkXlsxContent(
                List.of(row), ProductBulkXlsxContent.HeaderLanguage.EN), output);

        try (var workbook = WorkbookFactory.create(
                new ByteArrayInputStream(output.toByteArray()))) {
            var products = workbook.getSheet("Products");
            assertThat(products.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Code");
            assertThat(products.getRow(0).getCell(5).getStringCellValue())
                    .isEqualTo("Purchase price");
            assertThat(products.getRow(0).getCell(11).getStringCellValue())
                    .isEqualTo("Price use");
            assertThat(products.getColumnWidth(0)).isEqualTo(16 * 256);
        }
    }

    private static ProductBulkEditContent.Row row(
            UUID productId,
            ProductBulkEditContent.SupplierData supplier) {
        ProductBulkEditContent.ProductData product = new ProductBulkEditContent.ProductData(
                productId,
                0L,
                null,
                UUID.randomUUID().toString(),
                "P-1",
                "840000000001",
                "840000000002",
                "Producto",
                "Descripcion",
                "Comentario",
                "10.00",
                "5.00",
                "12.50",
                "11.50",
                "10.50",
                "9.50",
                "10.00",
                ProductType.UNIT.name(),
                PriceUseMode.NORMAL.name(),
                DiscountType.NORMAL.name(),
                UUID.randomUUID().toString(),
                "General",
                UUID.randomUUID().toString(),
                "Subfamilia",
                UUID.randomUUID().toString(),
                "IGIC",
                "true",
                "false",
                "2026-07-01",
                "2026-07-31",
                "GENERAL",
                "5.000",
                "8.000");
        return new ProductBulkEditContent.Row(
                "row-1",
                true,
                "P-1",
                product,
                ProductBulkEditContent.ProductData.empty(),
                List.of(supplier),
                null);
    }
}
