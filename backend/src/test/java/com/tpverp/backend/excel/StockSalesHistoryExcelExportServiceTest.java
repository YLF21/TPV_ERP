package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentStatus;
import com.tpverp.backend.inventory.StockSalesHistoryRow;
import com.tpverp.backend.inventory.StockSalesHistoryService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.CellType;
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
    void rejectsUnknownColumns() {
        var productId = UUID.randomUUID();
        prepareProduct(productId);

        assertThatThrownBy(() -> service.export(productId, new StockSalesHistoryExportRequest(
                null, null, null, labels(), List.of(
                        new StockSalesHistoryExportRequest.Column("secret", "Secreto")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Columna no permitida");
    }

    private void prepareProduct(UUID productId) {
        var store = mock(Store.class);
        var storeId = UUID.randomUUID();
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
        var product = mock(Product.class);
        when(product.getName()).thenReturn("Cafe molido");
        when(product.getCode()).thenReturn("CAFE-1");
        when(products.findAllByStoreIdAndIdIn(storeId, List.of(productId))).thenReturn(List.of(product));
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
