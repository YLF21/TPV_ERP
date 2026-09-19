package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.ProductType;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentStatus;
import com.tpverp.backend.inventory.StockSalesHistoryRow;
import com.tpverp.backend.inventory.StockSalesHistoryService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class StockSalesHistoryExcelExportServiceTest {

    private final CurrentOrganization organization = mock(CurrentOrganization.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final StockSalesHistoryService history = mock(StockSalesHistoryService.class);
    private final StockSalesHistoryExcelExportService service =
            new StockSalesHistoryExcelExportService(organization, products, history);

    @Test
    void exportsTypedRowsAndEffectiveTotalsWithoutCancelledDocuments() throws Exception {
        var productId = UUID.randomUUID();
        prepareProduct(productId);
        when(history.history(productId, LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-31")))
                .thenReturn(List.of(
                        row(DocumentStatus.CONFIRMADO, "2.00", "8.10", "T-1"),
                        row(DocumentStatus.ANULADO, "5.00", "50.00", "T-2"),
                        row(DocumentStatus.CONFIRMADO, "-1.00", "-4.50", "R-1")));

        var bytes = service.export(productId, request(null));

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Historial de ventas");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Cafe molido");
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("CAFE-1");
            assertThat(sheet.getRow(6).getCell(0).getStringCellValue()).isEqualTo("Cantidad");
            assertThat(sheet.getRow(7).getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(11).getCell(1).getNumericCellValue()).isEqualTo(1.0);
            assertThat(sheet.getRow(12).getCell(1).getNumericCellValue()).isEqualTo(3.6);
            assertThat(sheet.getPaneInformation()).isNotNull();
        }
    }

    @Test
    void appliesTheSelectedStatusBeforeWritingRowsAndTotals() throws Exception {
        var productId = UUID.randomUUID();
        prepareProduct(productId);
        when(history.history(productId, null, null)).thenReturn(List.of(
                row(DocumentStatus.CONFIRMADO, "2.00", "8.10", "T-1"),
                row(DocumentStatus.ANULADO, "5.00", "50.00", "T-2")));

        var bytes = service.export(productId, request(DocumentStatus.ANULADO, null, null));

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(7).getCell(0).getNumericCellValue()).isEqualTo(5.0);
            assertThat(sheet.getRow(9).getCell(1).getNumericCellValue()).isZero();
            assertThat(sheet.getRow(10).getCell(1).getNumericCellValue()).isZero();
        }
    }

    @Test
    void exportsMonochromeReadableColumnsWithoutChangingDatesDecimalsOrTotals() throws Exception {
        var productId = UUID.randomUUID();
        var product = prepareProduct(productId);
        when(product.getProductType()).thenReturn(ProductType.WEIGHT);
        when(product.getName()).thenReturn("Cafe molido de muestra - formato familiar para revisión del historial");
        String customer = "Cliente ficticio de demostración con una denominación comercial extensa "
                + "para comprobar que se conserva el nombre completo y se muestra en varias líneas";
        var sold = new StockSalesHistoryRow(
                UUID.randomUUID(), CommercialDocumentType.TICKET, "DEMO-2026-001", DocumentStatus.CONFIRMADO,
                Instant.parse("2026-07-10T12:30:00Z"), null, customer,
                new BigDecimal("3.125"), new BigDecimal("4.50"), new BigDecimal("7.50"), new BigDecimal("13.01"),
                null, "CAJA DEMO", UUID.randomUUID(), "Tienda de ejemplo", UUID.randomUUID(), "ALMACEN DEMO");
        when(history.history(productId, LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-31")))
                .thenReturn(List.of(sold,
                        row(DocumentStatus.ANULADO, "5.00", "50.00", "DEMO-ANULADO"),
                        row(DocumentStatus.CONFIRMADO, "-1.250", "-5.63", "R-DEMO")));
        var request = new StockSalesHistoryExportRequest(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-31"),
                null, labels(), List.of(
                        new StockSalesHistoryExportRequest.Column("occurredAt", "Fecha y hora"),
                        new StockSalesHistoryExportRequest.Column("document", "Documento"),
                        new StockSalesHistoryExportRequest.Column("status", "Estado"),
                        new StockSalesHistoryExportRequest.Column("customer", "Cliente"),
                        new StockSalesHistoryExportRequest.Column("quantity", "Cantidad"),
                        new StockSalesHistoryExportRequest.Column("unitPrice", "Precio unitario"),
                        new StockSalesHistoryExportRequest.Column("discount", "Descuento"),
                        new StockSalesHistoryExportRequest.Column("total", "Total"),
                        new StockSalesHistoryExportRequest.Column("user", "Usuario"),
                        new StockSalesHistoryExportRequest.Column("store", "Tienda"),
                        new StockSalesHistoryExportRequest.Column("warehouse", "Almacén")));

        var bytes = service.export(productId, request);

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            for (var excelRow : sheet) {
                for (var cell : excelRow) {
                    assertThat(cell.getCellStyle().getFillPattern()).isEqualTo(FillPatternType.NO_FILL);
                    assertThat(workbook.getFontAt(cell.getCellStyle().getFontIndex()).getColor())
                            .isEqualTo(IndexedColors.BLACK.getIndex());
                }
            }
            assertThat(sheet.getMergedRegions()).extracting(region -> region.formatAsString())
                    .contains("A1:K1", "B2:K2", "B3:K3", "B4:K4", "B5:K5");
            var data = sheet.getRow(7);
            assertThat(DateUtil.isCellDateFormatted(data.getCell(0))).isTrue();
            assertThat(data.getCell(0).getDateCellValue().toInstant()).isEqualTo(sold.occurredAt());
            assertThat(data.getCell(0).getCellStyle().getDataFormatString()).isEqualTo("dd/mm/yyyy hh:mm");
            assertThat(data.getCell(3).getStringCellValue()).isEqualTo(customer);
            assertThat(data.getCell(3).getCellStyle().getWrapText()).isTrue();
            assertThat(data.getHeightInPoints()).isGreaterThan(30);
            assertThat(data.getCell(4).getNumericCellValue()).isEqualTo(3.125);
            assertThat(data.getCell(4).getCellStyle().getDataFormatString()).isEqualTo("#,##0.###");
            assertThat(data.getCell(5).getNumericCellValue()).isEqualTo(4.5);
            assertThat(data.getCell(5).getCellStyle().getDataFormatString()).isEqualTo("#,##0.00 [$€-x-euro2]");
            assertThat(data.getCell(6).getNumericCellValue()).isEqualTo(7.5);
            assertThat(data.getCell(6).getCellStyle().getDataFormatString()).isEqualTo("#,##0.00\"%\"");
            assertThat(sheet.getRow(9).getCell(7).getNumericCellValue()).isEqualTo(-5.63);
            assertThat(sheet.getRow(11).getCell(1).getNumericCellValue()).isEqualTo(1.875);
            assertThat(sheet.getRow(12).getCell(1).getNumericCellValue()).isEqualTo(7.38);
            for (int column = 4; column <= 7; column++) {
                assertThat(data.getCell(column).getCellStyle().getAlignment()).isEqualTo(HorizontalAlignment.RIGHT);
                assertThat(sheet.getRow(6).getCell(column).getCellStyle().getAlignment()).isEqualTo(HorizontalAlignment.RIGHT);
            }
            assertThat(sheet.getColumnWidth(0)).isGreaterThanOrEqualTo(22 * 256);
            assertThat(sheet.getColumnWidth(3)).isLessThanOrEqualTo(48 * 256);
            assertThat(sheet.getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A7:K10");
            assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 7);
        }
        writePreview("stock-sales-history-clean.xlsx", bytes);
    }

    @Test
    void keepsMetadataAndTotalsReadableWithOnlyOneSelectedColumn() throws Exception {
        var productId = UUID.randomUUID();
        var product = prepareProduct(productId);
        String name = "Artículo ficticio con descripción larga para revisar el ajuste de texto "
                + "cuando el usuario exporta únicamente la cantidad y los metadatos siguen completos";
        when(product.getName()).thenReturn(name);
        when(history.history(productId, null, null))
                .thenReturn(List.of(row(DocumentStatus.CONFIRMADO, "2", "8.10", "DEMO-1")));
        var bytes = service.export(productId, new StockSalesHistoryExportRequest(null, null, null, labels(),
                List.of(new StockSalesHistoryExportRequest.Column("quantity", "Cantidad"))));

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getMergedRegion(0).formatAsString()).isEqualTo("A1:B1");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo(name);
            assertThat(sheet.getRow(1).getHeightInPoints()).isGreaterThan(40);
            assertThat(sheet.getColumnWidth(1)).isGreaterThanOrEqualTo(20 * 256);
            assertThat(sheet.getRow(6).getLastCellNum()).isEqualTo((short) 1);
            assertThat(sheet.getRow(7).getCell(0).getCellStyle().getDataFormatString()).isEqualTo("#,##0");
            assertThat(sheet.getRow(9).getCell(1).getNumericCellValue()).isEqualTo(2);
            assertThat(sheet.getRow(10).getCell(1).getNumericCellValue()).isEqualTo(8.1);
        }
        writePreview("stock-sales-history-single-column.xlsx", bytes);
    }

    @Test
    void rejectsUnknownColumns() {
        var productId = UUID.randomUUID();
        prepareProduct(productId);

        assertThatThrownBy(() -> service.export(productId, new StockSalesHistoryExportRequest(
                null, null, null, labels(), List.of(
                        new StockSalesHistoryExportRequest.Column("secret", "Secreto")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Columna no permitida");
    }

    private Product prepareProduct(UUID productId) {
        var store = mock(Store.class);
        var storeId = UUID.randomUUID();
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
        var product = mock(Product.class);
        when(product.getName()).thenReturn("Cafe molido");
        when(product.getCode()).thenReturn("CAFE-1");
        when(product.getProductType()).thenReturn(ProductType.UNIT);
        when(products.findAllByStoreIdAndIdIn(storeId, List.of(productId))).thenReturn(List.of(product));
        return product;
    }

    private static void writePreview(String name, byte[] bytes) throws Exception {
        var preview = Path.of("target/f6-history-export-review", name);
        Files.createDirectories(preview.getParent());
        Files.write(preview, bytes);
    }

    private static StockSalesHistoryExportRequest request(DocumentStatus status) {
        return request(status, LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-31"));
    }

    private static StockSalesHistoryExportRequest request(DocumentStatus status, LocalDate from, LocalDate to) {
        return new StockSalesHistoryExportRequest(from, to, status, labels(), List.of(
                new StockSalesHistoryExportRequest.Column("quantity", "Cantidad"),
                new StockSalesHistoryExportRequest.Column("total", "Total")));
    }

    private static StockSalesHistoryExportRequest.Labels labels() {
        return new StockSalesHistoryExportRequest.Labels(
                "Historial de ventas", "Producto", "Codigo", "Periodo", "Estado", "Todos",
                "Cantidad total vendida", "Importe total");
    }

    private static StockSalesHistoryRow row(
            DocumentStatus status, String quantity, String total, String number) {
        return new StockSalesHistoryRow(
                UUID.randomUUID(),
                number.startsWith("R") ? CommercialDocumentType.RECTIFICATIVA_VENTA : CommercialDocumentType.TICKET,
                number,
                status,
                Instant.parse("2026-07-10T12:30:00Z"),
                null,
                null,
                new BigDecimal(quantity),
                new BigDecimal("4.50"),
                BigDecimal.ZERO,
                new BigDecimal(total),
                null,
                "ADMIN",
                UUID.randomUUID(),
                "Principal",
                UUID.randomUUID(),
                "GENERAL");
    }
}
