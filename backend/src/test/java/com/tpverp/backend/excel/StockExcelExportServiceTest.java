package com.tpverp.backend.excel;

import com.tpverp.backend.inventory.StockTopSalesService;
import com.tpverp.backend.inventory.StockTopSalesRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;

class StockExcelExportServiceTest {

    @Test
    void writesAllRowsUsingTheColumnOrderReceivedFromTheGrid() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var result = mock(ResultSet.class);
        when(result.next()).thenReturn(true, false);
        when(result.getString("name")).thenReturn("Café molido");
        when(result.getString("code")).thenReturn("CAF-001");
        when(result.getBigDecimal("package_quantity")).thenReturn(BigDecimal.ONE);
        when(result.getBigDecimal("local_stock")).thenReturn(new BigDecimal("108"));
        when(result.getBigDecimal("total_stock")).thenReturn(new BigDecimal("193"));
        when(result.getBigDecimal("purchase_price")).thenReturn(new BigDecimal("1.67"));
        when(jdbc.query(any(PreparedStatementCreator.class),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<Object>>any()))
                .thenAnswer(invocation -> {
                    ResultSetExtractor<?> extractor = invocation.getArgument(1);
                    return extractor.extractData(result);
                });
        var service = new StockExcelExportService(jdbc, mock(StockTopSalesService.class));
        var storeId = UUID.randomUUID();
        var request = new StockExcelExportService.ExportRequest(
                null, null, null, null, null, null, null, null, null, null,
                "name", "asc", "es", null, null, null, null, null,
                List.of(
                        new StockExcelExportService.ExportColumn("name", "Nombre"),
                        new StockExcelExportService.ExportColumn("code", "Código"),
                        new StockExcelExportService.ExportColumn("packageQuantity", "Cantidad"),
                        new StockExcelExportService.ExportColumn("localStock", "Stock local"),
                        new StockExcelExportService.ExportColumn("totalStock", "Stock total"),
                        new StockExcelExportService.ExportColumn("purchasePrice", "Precio compra")));
        var job = service.create(storeId, "ADMIN", true, request);

        service.run(job.id());

        var completed = service.status(job.id(), storeId, "ADMIN");
        assertThat(completed.status())
                .isEqualTo(StockExcelExportService.JobStatus.COMPLETED);
        assertThat(completed.processedRows()).isOne();
        var file = service.file(job.id(), storeId, "ADMIN");
        try (var workbook = new XSSFWorkbook(Files.newInputStream(file.path()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Nombre");
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Código");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Café molido");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("CAF-001");
            assertThat(sheet.getRow(1).getCell(2).getNumericCellValue()).isEqualTo(1d);
            assertThat(sheet.getRow(1).getCell(3).getNumericCellValue()).isEqualTo(108d);
            assertThat(sheet.getRow(1).getCell(4).getNumericCellValue()).isEqualTo(193d);
            assertThat(sheet.getRow(1).getCell(5).getNumericCellValue()).isEqualTo(1.67d);
            assertThat(sheet.getRow(1).getCell(2).getCellStyle().getDataFormatString())
                    .isEqualTo("#,##0");
            assertThat(sheet.getRow(1).getCell(3).getCellStyle().getDataFormatString())
                    .isEqualTo("#,##0");
            assertThat(sheet.getRow(1).getCell(4).getCellStyle().getDataFormatString())
                    .isEqualTo("#,##0");
            assertThat(sheet.getRow(1).getCell(5).getCellStyle().getDataFormatString())
                    .contains("€");
        } finally {
            Files.deleteIfExists(file.path());
        }
    }

    @Test
    void removesPurchaseColumnsWhenTheUserCannotViewPurchaseData() {
        var service = new StockExcelExportService(mock(JdbcTemplate.class), mock(StockTopSalesService.class));
        var request = new StockExcelExportService.ExportRequest(
                null, null, null, null, null, null, null, null, null, null,
                "name", "asc", "es", null, null, null, null, null,
                List.of(new StockExcelExportService.ExportColumn(
                        "purchasePrice", "Precio compra")));

        assertThatThrownBy(() -> service.create(UUID.randomUUID(), "USER", false, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("stock_excel_export_columns_required");
    }

    @Test
    void writesTopSalesInConfiguredOrderWithCurrencyAmount() throws Exception {
        var topSales = mock(StockTopSalesService.class);
        var storeId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        when(topSales.topSales(storeId, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 17), null)).thenReturn(List.of(
                new StockTopSalesRow(productId, "CAF-001", "8410000000011", "Café",
                        null, "Bebidas", null, "-", List.of(),
                        new BigDecimal("4"), new BigDecimal("12.10"),
                        new BigDecimal("8"), UUID.randomUUID(), "GENERAL")));
        var service = new StockExcelExportService(mock(JdbcTemplate.class), topSales);
        var request = new StockExcelExportService.ExportRequest(
                "TOP_SALES", null, null, null, null, null, null, null, null, null,
                "ranking", "asc", "es", LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 17), null, null, null,
                List.of(
                        new StockExcelExportService.ExportColumn("name", "Nombre"),
                        new StockExcelExportService.ExportColumn("amount", "Importe"),
                        new StockExcelExportService.ExportColumn("ranking", "Posición")));
        var job = service.create(storeId, "ADMIN", true, request);

        service.run(job.id());

        var file = service.file(job.id(), storeId, "ADMIN");
        try (var workbook = new XSSFWorkbook(Files.newInputStream(file.path()))) {
            var row = workbook.getSheetAt(0).getRow(1);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("Café");
            assertThat(row.getCell(1).getNumericCellValue()).isEqualTo(12.10d);
            assertThat(row.getCell(1).getCellStyle().getDataFormatString()).contains("€");
            assertThat(row.getCell(2).getNumericCellValue()).isEqualTo(1d);
        } finally {
            Files.deleteIfExists(file.path());
        }
    }
}
