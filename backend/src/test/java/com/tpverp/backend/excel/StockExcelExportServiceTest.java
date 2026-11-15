package com.tpverp.backend.excel;

import com.tpverp.backend.inventory.StockTopSalesService;
import com.tpverp.backend.inventory.StockTopSalesRow;
import com.tpverp.backend.inventory.StockListFilterChoices;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

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
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<StockExportLayout.Metadata>>any(),
                eq(storeId))).thenReturn(List.of(new StockExportLayout.Metadata(
                        "Cafés Canarias, S.L.", "B12345678", "001 - Centro", "Calle Mayor 1",
                        null, "Atlantic/Canary", Instant.parse("2026-11-14T10:30:00Z"))));
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
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("INFORME DE STOCK ACTUAL");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Cafés Canarias, S.L.");
            assertThat(sheet.getRow(3).getCell(1).getStringCellValue()).isEqualTo("001 - Centro");
            assertThat(sheet.getRow(8).getCell(0).getStringCellValue()).isEqualTo("Nombre");
            assertThat(sheet.getRow(8).getCell(1).getStringCellValue()).isEqualTo("Código");
            assertThat(sheet.getRow(9).getCell(0).getStringCellValue()).isEqualTo("Café molido");
            assertThat(sheet.getRow(9).getCell(1).getStringCellValue()).isEqualTo("CAF-001");
            assertThat(sheet.getRow(9).getCell(2).getNumericCellValue()).isEqualTo(1d);
            assertThat(sheet.getRow(9).getCell(3).getNumericCellValue()).isEqualTo(108d);
            assertThat(sheet.getRow(9).getCell(4).getNumericCellValue()).isEqualTo(193d);
            assertThat(sheet.getRow(9).getCell(5).getNumericCellValue()).isEqualTo(1.67d);
            assertThat(sheet.getRow(9).getCell(3).getCellStyle().getDataFormatString())
                    .isEqualTo("#,##0");
            assertThat(sheet.getRow(9).getCell(5).getCellStyle().getDataFormatString())
                    .contains("€");
            assertThat(sheet.getRow(10).getCell(0).getStringCellValue()).isEqualTo("TOTAL");
            assertThat(sheet.getRow(10).getCell(3).getNumericCellValue()).isEqualTo(108d);
            assertThat(sheet.getRow(10).getCell(4).getNumericCellValue()).isEqualTo(193d);
            assertThat(sheet.getRow(10).getCell(5).getStringCellValue()).isEmpty();
            assertThat(sheet.getRow(11).getCell(0).getStringCellValue()).isEqualTo("Productos: 1");
            assertThat(workbook.getFontAt(sheet.getRow(10).getCell(0).getCellStyle().getFontIndex())
                    .getFontHeightInPoints()).isEqualTo((short) 14);
            assertThat(file.fileName()).matches("Stock_\\d{6}\\.xlsx");
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
    void resolvesSelectedWarehouseNamesOnlyWithinTheJobStore() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var storeId = UUID.randomUUID();
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<StockExportLayout.Metadata>>any(),
                eq(storeId))).thenReturn(List.of(new StockExportLayout.Metadata(
                        "Empresa", "B12345678", "001 - Centro", "Dirección", null,
                        "Atlantic/Canary", Instant.now())));
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<StockExportLayout.Warehouse>>any(),
                eq(storeId), eq(first + "," + second))).thenReturn(List.of(
                        new StockExportLayout.Warehouse(first, "Central"),
                        new StockExportLayout.Warehouse(second, "Tienda")));
        var choices = new StockListFilterChoices(null, null, null, null, null, null, null,
                List.of(first, second), null, null, null);
        var request = new StockExcelExportService.ExportRequest("CURRENT_STOCK", null, null, null,
                null, null, null, null, null, null, "name", "asc", "es",
                null, null, null, null, null,
                List.of(new StockExcelExportService.ExportColumn("name", "Nombre")),
                null, null, choices);
        var service = new StockExcelExportService(jdbc, mock(StockTopSalesService.class));
        var job = service.create(storeId, "ADMIN", false, request);
        service.run(job.id());
        var file = service.file(job.id(), storeId, "ADMIN");
        try (var workbook = new XSSFWorkbook(Files.newInputStream(file.path()))) {
            assertThat(workbook.getSheetAt(0).getRow(5).getCell(0).getStringCellValue())
                    .isEqualTo("Almacén: Central, Tienda");
            verify(jdbc).query(org.mockito.ArgumentMatchers.argThat((String sql) ->
                            sql.contains("warehouse.tienda_id = ?")),
                    org.mockito.ArgumentMatchers.<RowMapper<StockExportLayout.Warehouse>>any(),
                    eq(storeId), eq(first + "," + second));
        } finally {
            Files.deleteIfExists(file.path());
        }
    }

    @Test
    void topSalesWarehouseHeaderUsesIntersectionOfSingleAndMultipleFilters() throws Exception {
        var storeId = UUID.randomUUID();
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        for (boolean matching : List.of(true, false)) {
            var jdbc = metadataJdbc(storeId);
            var selected = matching ? List.of(first, second) : List.of(second);
            String requested = matching ? first + "," + second : second + "," + first;
            when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<StockExportLayout.Warehouse>>any(),
                    eq(storeId), eq(requested))).thenReturn(List.of(
                            new StockExportLayout.Warehouse(first, "Almacén A"),
                            new StockExportLayout.Warehouse(second, "Almacén B")));
            var choices = new StockListFilterChoices(null, null, null, null, null, null, null,
                    null, selected, null, null);
            var request = new StockExcelExportService.ExportRequest("TOP_SALES", null, null, null,
                    null, null, null, null, null, first, "ranking", "asc", "es",
                    LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 17), null, null, null,
                    List.of(new StockExcelExportService.ExportColumn("name", "Nombre")),
                    null, null, choices);
            var topSales = mock(StockTopSalesService.class);
            var service = new StockExcelExportService(jdbc, topSales);
            var job = service.create(storeId, "ADMIN", false, request);
            service.run(job.id());
            var file = service.file(job.id(), storeId, "ADMIN");
            try (var workbook = new XSSFWorkbook(Files.newInputStream(file.path()))) {
                assertThat(workbook.getSheetAt(0).getRow(5).getCell(0).getStringCellValue())
                        .isEqualTo(matching ? "Almacén: Almacén A"
                                : "Almacén: Sin almacenes coincidentes");
                verify(topSales).topSalesFiltered(storeId, request.dateFrom(), request.dateTo(), first, selected);
            } finally {
                Files.deleteIfExists(file.path());
            }
        }
    }

    @Test
    void singleColumnKeepsMultilineContentAndEmptySummary() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var storeId = UUID.randomUUID();
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<StockExportLayout.Metadata>>any(),
                eq(storeId))).thenReturn(List.of(new StockExportLayout.Metadata(
                        "Empresa con nombre muy largo y varias palabras que deben quedar visibles",
                        "B12345678", "001 - Centro", "Calle Primera\nCalle Segunda",
                        null, "Atlantic/Canary", Instant.now())));
        var service = new StockExcelExportService(jdbc, mock(StockTopSalesService.class));
        var request = new StockExcelExportService.ExportRequest("CURRENT_STOCK", null, null, null,
                null, null, null, null, null, null, "name", "asc", "zh",
                null, null, null, null, null,
                List.of(new StockExcelExportService.ExportColumn("name", "名称")));
        var job = service.create(storeId, "ADMIN", false, request);
        service.run(job.id());
        var file = service.file(job.id(), storeId, "ADMIN");
        var designDirectory = Path.of("target", "stock-export-design");
        Files.createDirectories(designDirectory);
        Files.copy(file.path(), designDirectory.resolve("current-stock-empty.xlsx"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (var workbook = new XSSFWorkbook(Files.newInputStream(file.path()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(1).getHeightInPoints()).isGreaterThan(27);
            assertThat(sheet.getRow(4).getHeightInPoints()).isGreaterThan(27);
            assertThat(sheet.getRow(9).getCell(0).getStringCellValue()).isEqualTo("TOTAL");
            assertThat(sheet.getRow(10).getCell(0).getStringCellValue()).isEqualTo("商品数: 0");
            assertThat(sheet.getPrintSetup().getLandscape()).isFalse();
        } finally {
            Files.deleteIfExists(file.path());
        }
    }

    @Test
    void pdfColumnGroupsKeepRequestedOrderWithoutAddingOrRepeatingAttributes() {
        var columns = List.of("barcode", "supplier", "family", "subfamily", "tax", "taxIncluded",
                "salePrice", "memberPrice", "wholesalePrice", "offerPrice", "localStock", "totalStock")
                .stream().map(key -> new StockExcelExportService.ExportColumn(key, key)).toList();
        var request = new StockExcelExportService.ExportRequest("OFFERS", null, null, null,
                null, null, null, null, null, null, "name", "asc", "es",
                null, null, null, null, null, columns);
        var groups = StockExportLayout.groups(request);
        assertThat(groups.size()).isGreaterThan(1);
        for (var group : groups) {
            assertThat(group.stream().mapToInt(column -> StockExportLayout.width(column.key())).sum())
                    .isLessThanOrEqualTo(802);
        }
        assertThat(groups.stream().flatMap(List::stream)
                .map(StockExcelExportService.ExportColumn::key).toList())
                .containsExactlyElementsOf(columns.stream().map(StockExcelExportService.ExportColumn::key).toList());
        var narrow = new StockExcelExportService.ExportRequest("OFFERS", null, null, null,
                null, null, null, null, null, null, "name", "asc", "es",
                null, null, null, null, null,
                List.of(new StockExcelExportService.ExportColumn("name", "Nombre"),
                        new StockExcelExportService.ExportColumn("code", "Código")));
        assertThat(StockExportLayout.groups(narrow)).containsExactly(narrow.columns());
    }

    @ParameterizedTest
    @CsvSource({"CURRENT_STOCK", "TOP_SALES", "OFFERS", "MEMBER_PRICE", "NO_DISCOUNT"})
    void wideExportsKeepEveryAttributeAndRowInOneSheet(String view) throws Exception {
        var storeId = UUID.randomUUID();
        var jdbc = metadataJdbc(storeId);
        var topSales = mock(StockTopSalesService.class);
        var from = LocalDate.of(2026, 8, 1);
        var to = LocalDate.of(2026, 8, 17);
        int rowCount = 205; // Cross the SXSSF in-memory window.
        var result = mock(ResultSet.class);
        var position = new java.util.concurrent.atomic.AtomicInteger();
        when(result.next()).thenAnswer(ignored -> position.incrementAndGet() <= rowCount);
        when(result.getString(anyString())).thenAnswer(invocation ->
                "name".equals(invocation.getArgument(0)) ? "Producto " + position.get() : "Detalle");
        when(result.getBigDecimal(anyString())).thenReturn(BigDecimal.ONE);
        when(jdbc.query(any(PreparedStatementCreator.class),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<Object>>any()))
                .thenAnswer(invocation -> ((ResultSetExtractor<?>) invocation.getArgument(1)).extractData(result));
        when(topSales.topSales(storeId, from, to, null)).thenReturn(java.util.stream.IntStream
                .rangeClosed(1, rowCount).mapToObj(index -> new StockTopSalesRow(UUID.randomUUID(),
                        "P" + index, "8410000000011", "Producto " + index,
                        null, "Familia", null, "Subfamilia", List.of(),
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, UUID.randomUUID(), "GENERAL"))
                .toList());
        var keys = "TOP_SALES".equals(view)
                ? List.of("name", "barcode", "supplier", "family", "subfamily", "ranking",
                        "soldUnits", "amount", "currentStock", "warehouse")
                : List.of("name", "barcode", "supplier", "family", "subfamily", "tax", "taxIncluded",
                        "salePrice", "memberPrice", "wholesalePrice", "localStock", "totalStock");
        var columns = keys.stream().map(key -> new StockExcelExportService.ExportColumn(key, key)).toList();
        var request = new StockExcelExportService.ExportRequest(view, null, null, null,
                null, null, null, null, null, null, "TOP_SALES".equals(view) ? "ranking" : "name",
                "asc", "es", from, to, null, null, null, columns);
        var service = new StockExcelExportService(jdbc, topSales);
        var job = service.create(storeId, "ADMIN", true, request);
        service.run(job.id());
        var file = service.file(job.id(), storeId, "ADMIN");
        try (var workbook = new XSSFWorkbook(Files.newInputStream(file.path()))) {
            assertThat(workbook.getNumberOfSheets()).isOne();
            var sheet = workbook.getSheetAt(0);
            var header = sheet.getRow(StockExportLayout.HEADER_ROW);
            assertThat(header.getLastCellNum()).isEqualTo((short) columns.size());
            for (int column = 0; column < columns.size(); column++) {
                assertThat(header.getCell(column).getStringCellValue()).isEqualTo(keys.get(column));
            }
            for (int row = 1; row <= rowCount; row++) {
                assertThat(sheet.getRow(StockExportLayout.HEADER_ROW + row).getCell(0).getStringCellValue())
                        .isEqualTo("Producto " + row);
            }
            var total = sheet.getRow(StockExportLayout.HEADER_ROW + rowCount + 1);
            for (var key : StockExportLayout.totalKeys(request)) {
                int column = keys.indexOf(key);
                if (column >= 0) assertThat(total.getCell(column).getNumericCellValue()).isEqualTo(rowCount);
            }
            assertThat(service.status(job.id(), storeId, "ADMIN").processedRows()).isEqualTo(rowCount);
        } finally {
            Files.deleteIfExists(file.path());
        }
    }

    @ParameterizedTest
    @CsvSource({"CURRENT_STOCK", "TOP_SALES", "OFFERS", "MEMBER_PRICE", "NO_DISCOUNT"})
    void singleColumnReportsKeepFullTitleAndA4(String view) throws Exception {
        var storeId = UUID.randomUUID();
        var service = new StockExcelExportService(metadataJdbc(storeId), mock(StockTopSalesService.class));
        var request = new StockExcelExportService.ExportRequest(view, null, null, null,
                null, null, null, null, null, null,
                "TOP_SALES".equals(view) ? "ranking" : "name", "asc", "es",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 17), null, null, null,
                List.of(new StockExcelExportService.ExportColumn("name", "Nombre")));
        var job = service.create(storeId, "ADMIN", false, request);
        service.run(job.id());
        var file = service.file(job.id(), storeId, "ADMIN");
        var designDirectory = Path.of("target", "stock-export-design");
        Files.createDirectories(designDirectory);
        Files.copy(file.path(), designDirectory.resolve(view.toLowerCase(Locale.ROOT) + "-single-column.xlsx"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (var workbook = new XSSFWorkbook(Files.newInputStream(file.path()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo(StockExportLayout.title(request));
            assertThat(sheet.getRow(0).getHeightInPoints())
                    .isGreaterThanOrEqualTo(36);
            if ("MEMBER_PRICE".equals(view) || "NO_DISCOUNT".equals(view)) {
                assertThat(sheet.getRow(0).getHeightInPoints()).isGreaterThan(36);
            }
            assertThat(sheet.getPrintSetup().getPaperSize())
                    .isEqualTo(org.apache.poi.ss.usermodel.PrintSetup.A4_PAPERSIZE);
            assertThat(sheet.getPrintSetup().getLandscape()).isFalse();
        } finally {
            Files.deleteIfExists(file.path());
        }
    }

    @ParameterizedTest
    @CsvSource({"code,asc", "code,desc", "ranking,asc", "ranking,desc"})
    void topSalesExportsNaturalCodeOrderWithoutChangingSalesRank(String sortBy, String direction) throws Exception {
        var topSales = mock(StockTopSalesService.class);
        var storeId = UUID.randomUUID();
        var from = LocalDate.of(2026, 8, 1);
        var to = LocalDate.of(2026, 8, 17);
        var rankedCodes = List.of("10", "2", "1", "P10", "P02", "P2");
        when(topSales.topSales(storeId, from, to, null)).thenReturn(rankedCodes.stream()
                .map(code -> new StockTopSalesRow(UUID.randomUUID(), code, "",
                        List.of("A", "B", "a", "b", "z", "c").get(rankedCodes.indexOf(code)),
                        null, "GENERAL", null, "-", List.of(),
                        BigDecimal.valueOf(100 - Math.max(0, rankedCodes.indexOf(code) - 3)), BigDecimal.ONE,
                        BigDecimal.ONE, UUID.randomUUID(), "GENERAL"))
                .toList());
        var service = new StockExcelExportService(metadataJdbc(storeId), topSales);
        var request = new StockExcelExportService.ExportRequest(
                "TOP_SALES", null, null, null, null, null, null, null, null, null,
                sortBy, direction, "es", from, to, null, null, null,
                List.of(new StockExcelExportService.ExportColumn("code", "Codigo"),
                        new StockExcelExportService.ExportColumn("ranking", "Posicion")));
        var job = service.create(storeId, "ADMIN", true, request);
        service.run(job.id());
        var file = service.file(job.id(), storeId, "ADMIN");
        var expectedCodes = "code".equals(sortBy)
                ? ("asc".equals(direction) ? List.of("1", "2", "10", "P02", "P2", "P10")
                        : List.of("P10", "P02", "P2", "10", "2", "1"))
                : ("asc".equals(direction) ? rankedCodes : rankedCodes.reversed());
        try (var workbook = new XSSFWorkbook(Files.newInputStream(file.path()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(StockExportLayout.HEADER_ROW + expectedCodes.size() + 2);
            for (int index = 0; index < expectedCodes.size(); index++) {
                var row = sheet.getRow(StockExportLayout.HEADER_ROW + index + 1);
                assertThat(row.getCell(0).getStringCellValue()).isEqualTo(expectedCodes.get(index));
                assertThat(row.getCell(1).getNumericCellValue())
                        .isEqualTo(rankedCodes.indexOf(expectedCodes.get(index)) + 1);
            }
        } finally {
            Files.deleteIfExists(file.path());
        }
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
        var service = new StockExcelExportService(metadataJdbc(storeId), topSales);
        var request = new StockExcelExportService.ExportRequest(
                "TOP_SALES", null, null, null, null, null, null, null, null, null,
                "ranking", "asc", "es", LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 17), null, null, null,
                List.of(
                        new StockExcelExportService.ExportColumn("name", "Nombre"),
                        new StockExcelExportService.ExportColumn("amount", "Importe"),
                         new StockExcelExportService.ExportColumn("ranking", "Posición"),
                         new StockExcelExportService.ExportColumn("soldUnits", "Unidades vendidas"),
                         new StockExcelExportService.ExportColumn("currentStock", "Stock actual")));
        var job = service.create(storeId, "ADMIN", true, request);

        service.run(job.id());

        var file = service.file(job.id(), storeId, "ADMIN");
        try (var workbook = new XSSFWorkbook(Files.newInputStream(file.path()))) {
            var sheet = workbook.getSheetAt(0);
            var row = sheet.getRow(StockExportLayout.HEADER_ROW + 1);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("Café");
            assertThat(row.getCell(1).getNumericCellValue()).isEqualTo(12.10d);
            assertThat(row.getCell(1).getCellStyle().getDataFormatString()).contains("€");
            assertThat(row.getCell(2).getNumericCellValue()).isEqualTo(1d);
            assertThat(row.getCell(3).getNumericCellValue()).isEqualTo(4d);
            assertThat(row.getCell(4).getNumericCellValue()).isEqualTo(8d);
            var total = sheet.getRow(StockExportLayout.HEADER_ROW + 2);
            assertThat(total.getCell(1).getNumericCellValue()).isEqualTo(12.10d);
            assertThat(total.getCell(2).getStringCellValue()).isEmpty();
            assertThat(total.getCell(3).getNumericCellValue()).isEqualTo(4d);
            assertThat(total.getCell(4).getNumericCellValue()).isEqualTo(8d);
            assertThat(sheet.getRow(StockExportLayout.HEADER_ROW + 3).getCell(0).getStringCellValue())
                    .isEqualTo("Registros: 1");
            assertThat(sheet.getRow(7).getCell(0).getStringCellValue())
                    .contains("01/08/2026", "17/08/2026");
        } finally {
            Files.deleteIfExists(file.path());
        }
    }

    @ParameterizedTest
    @CsvSource({"SELECTED,es", "ACTIVE,es", "SELECTED,en", "ACTIVE,en", "SELECTED,zh", "ACTIVE,zh"})
    void promotionWorkbooksUseDocumentStyleAndKeepTheirRowsAndValues(String scope, String language) throws Exception {
        var storeId = UUID.randomUUID();
        var jdbc = metadataJdbc(storeId);
        var promotionId = "SELECTED".equals(scope) ? UUID.randomUUID() : null;
        if (promotionId != null) {
            when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<StockExcelExportService.PromotionExportDetails>>any(),
                    eq(storeId), eq(promotionId))).thenReturn(List.of(promotionDetails("Café / Verano 夏")));
        }
        var result = mock(ResultSet.class);
        when(result.next()).thenReturn(true, true, false);
        when(result.getString("code")).thenReturn("CAF-1", "CAF-2");
        when(result.getString("name")).thenReturn("Café de promoción\nDescripción completa");
        when(result.getString("promotion_names")).thenReturn("Café / Verano 夏");
        when(result.getString("promotion_types")).thenReturn("Descuento 10 %");
        when(result.getString("promotion_validity")).thenReturn("01/11/2026 - 30/11/2026");
        when(result.getBigDecimal("local_stock")).thenReturn(new BigDecimal("1.125"));
        when(result.getBigDecimal("sale_price")).thenReturn(new BigDecimal("1.675"));
        when(jdbc.query(any(PreparedStatementCreator.class),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<Object>>any()))
                .thenAnswer(invocation -> ((ResultSetExtractor<?>) invocation.getArgument(1)).extractData(result));
        var keys = List.of("code", "name", "promotion", "promotionType", "promotionValidity", "stock", "salePrice");
        var labels = List.of("Código", "Nombre", "Promoción", "Tipo", "Vigencia", "Stock", "Precio venta");
        var columns = java.util.stream.IntStream.range(0, keys.size()).mapToObj(index ->
                new StockExcelExportService.ExportColumn(keys.get(index), labels.get(index))).toList();
        var request = new StockExcelExportService.ExportRequest("PROMOTIONS", null, null, null,
                null, null, null, null, null, null, "name", "asc", language,
                null, null, null, null, null, columns, scope, promotionId);
        var service = new StockExcelExportService(jdbc, mock(StockTopSalesService.class));
        var job = service.create(storeId, "ADMIN", true, request);
        service.run(job.id());
        var file = service.file(job.id(), storeId, "ADMIN");
        var directory = Path.of("target", "promotions-export-design");
        Files.createDirectories(directory);
        Files.copy(file.path(), directory.resolve(scope.toLowerCase(Locale.ROOT) + "-" + language + ".xlsx"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (var workbook = new XSSFWorkbook(Files.newInputStream(file.path()))) {
            assertThat(workbook.getNumberOfSheets()).isOne();
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo(StockExportLayout.title(request));
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Empresa");
            assertThat(sheet.getRow(3).getCell(1).getStringCellValue()).isEqualTo("001 - Centro");
            assertThat(sheet.getRow(7).getCell(0).getStringCellValue()).contains(promotionId == null
                    ? StockExportLayout.translate(language, "Todas las promociones activas", "All active promotions", "所有有效促销")
                    : "Café / Verano 夏");
            assertThat(sheet.getRow(8).getCell(0).getCellStyle().getFillForegroundXSSFColor().getARGBHex())
                    .isEqualTo("FF0A2F56");
            assertThat(sheet.getRow(9).getCell(0).getStringCellValue()).isEqualTo("CAF-1");
            assertThat(sheet.getRow(10).getCell(0).getStringCellValue()).isEqualTo("CAF-2");
            assertThat(sheet.getRow(9).getCell(1).getStringCellValue()).contains("\nDescripción completa");
            assertThat(sheet.getRow(9).getHeightInPoints()).isGreaterThan(28);
            assertThat(sheet.getRow(9).getCell(6).getNumericCellValue()).isEqualTo(1.675);
            assertThat(sheet.getRow(9).getCell(6).getCellStyle().getDataFormatString()).contains("€");
            assertThat(sheet.getRow(11).getCell(5).getNumericCellValue()).isEqualTo(2.25);
            var totalFont = workbook.getFontAt(sheet.getRow(11).getCell(0).getCellStyle().getFontIndex());
            assertThat(totalFont.getFontName()).isEqualTo("Calibri");
            assertThat(totalFont.getBold()).isTrue();
            assertThat(totalFont.getFontHeightInPoints()).isEqualTo((short) 14);
            assertThat(service.status(job.id(), storeId, "ADMIN").processedRows()).isEqualTo(2);
        } finally {
            Files.deleteIfExists(file.path());
        }
    }

    @Test
    void selectedPromotionIsStoreScopedAndKeepsItsNameWhenTheJobCompletes() throws Exception {
        var storeId = UUID.randomUUID();
        var jdbc = metadataJdbc(storeId);
        var promotionId = UUID.randomUUID();
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<StockExcelExportService.PromotionExportDetails>>any(),
                eq(storeId), eq(promotionId))).thenReturn(List.of(promotionDetails("Café / Verano 25%")));
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString(), eq(ResultSet.TYPE_FORWARD_ONLY),
                eq(ResultSet.CONCUR_READ_ONLY))).thenReturn(statement);
        when(jdbc.query(any(PreparedStatementCreator.class),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<Object>>any()))
                .thenAnswer(invocation -> {
                    PreparedStatementCreator creator = invocation.getArgument(0);
                    creator.createPreparedStatement(connection);
                    return null;
                });
        var service = new StockExcelExportService(jdbc, mock(StockTopSalesService.class));
        var job = service.create(storeId, "ADMIN", false,
                promotionRequest("SELECTED", promotionId));
        verify(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<StockExcelExportService.PromotionExportDetails>>any(),
                eq(storeId), eq(promotionId));
        service.run(job.id());
        var file = service.file(job.id(), storeId, "ADMIN");
        try {
            assertThat(file.fileName()).isEqualTo("Café Verano 25%_011126-301126.xlsx");
            verify(statement).setObject(15, promotionId);
            verify(connection).prepareStatement(org.mockito.ArgumentMatchers.contains(
                    "selected_promotion.id = filter.promotion_id"),
                    eq(ResultSet.TYPE_FORWARD_ONLY), eq(ResultSet.CONCUR_READ_ONLY));
        } finally {
            Files.deleteIfExists(file.path());
        }
    }

    @Test
    void activePromotionExportUsesDateBoundsAndOneProductRow() throws Exception {
        var storeId = UUID.randomUUID();
        var jdbc = metadataJdbc(storeId);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString(), eq(ResultSet.TYPE_FORWARD_ONLY),
                eq(ResultSet.CONCUR_READ_ONLY))).thenReturn(statement);
        when(jdbc.query(any(PreparedStatementCreator.class),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<Object>>any()))
                .thenAnswer(invocation -> {
                    PreparedStatementCreator creator = invocation.getArgument(0);
                    creator.createPreparedStatement(connection);
                    return null;
                });
        var service = new StockExcelExportService(jdbc, mock(StockTopSalesService.class));
        var job = service.create(storeId, "ADMIN", false, promotionRequest(null, null));
        service.run(job.id());
        var file = service.file(job.id(), storeId, "ADMIN");
        try {
            assertThat(file.fileName()).isEqualTo("Promociones_141126.xlsx");
            verify(statement).setObject(14, "ACTIVE");
            verify(statement).setObject(16, LocalDate.now());
            verify(connection).prepareStatement(org.mockito.ArgumentMatchers.argThat(sql ->
                    sql.contains("selected_promotion.fecha_inicio <= filter.export_date")
                    && sql.contains("selected_promotion.fecha_fin >= filter.export_date")
                    && sql.contains("selected_promotion.estado = 'ACTIVE'")
                    && sql.contains("string_agg(distinct selected_promotion.nombre")
                    && sql.contains("where product.tienda_id = filter.store_id")),
                    eq(ResultSet.TYPE_FORWARD_ONLY), eq(ResultSet.CONCUR_READ_ONLY));
        } finally {
            Files.deleteIfExists(file.path());
        }
    }

    @Test
    void rejectsPromotionOutsideStoreCompanyAndInvalidScopeCombinations() {
        var jdbc = mock(JdbcTemplate.class);
        var service = new StockExcelExportService(jdbc, mock(StockTopSalesService.class));
        var storeId = UUID.randomUUID();
        var promotionId = UUID.randomUUID();
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<StockExcelExportService.PromotionExportDetails>>any(),
                eq(storeId), eq(promotionId))).thenReturn(List.of());
        assertThatThrownBy(() -> service.create(storeId, "ADMIN", false,
                promotionRequest("SELECTED", promotionId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("stock_excel_export_promotion_not_found");
        assertThatThrownBy(() -> service.create(storeId, "ADMIN", false,
                promotionRequest("SELECTED", null)))
                .hasMessage("stock_excel_export_promotion_scope_invalid");
        assertThatThrownBy(() -> service.create(storeId, "ADMIN", false,
                promotionRequest("ACTIVE", promotionId)))
                .hasMessage("stock_excel_export_promotion_scope_invalid");
        assertThatThrownBy(() -> service.create(storeId, "ADMIN", false,
                promotionRequest("OTHER", null)))
                .hasMessage("stock_excel_export_promotion_scope_invalid");
    }

    @Test
    void doesNotReturnAnUnrelatedQueuedJob() {
        var storeId = UUID.randomUUID();
        var service = new StockExcelExportService(metadataJdbc(storeId), mock(StockTopSalesService.class));
        var active = promotionRequest("ACTIVE", null);
        var queued = service.create(storeId, "ADMIN", false, active);
        assertThat(service.create(storeId, "ADMIN", false, promotionRequest(null, null)).id())
                .isEqualTo(queued.id());
        assertThatThrownBy(() -> service.create(storeId, "ADMIN", false,
                promotionRequest("SELECTED", UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("stock_excel_export_busy");
    }

    @ParameterizedTest
    @CsvSource({
            "CURRENT_STOCK,es,Stock",
            "OFFERS,es,Ofertas",
            "MEMBER_PRICE,es,Precio_de_socio",
            "NO_DISCOUNT,es,Sin_descuento",
            "TOP_SALES,es,Top_ventas",
            "CURRENT_STOCK,en,Stock",
            "OFFERS,en,Ofertas",
            "MEMBER_PRICE,en,Precio_de_socio",
            "NO_DISCOUNT,en,Sin_descuento",
            "TOP_SALES,en,Top_ventas",
            "CURRENT_STOCK,zh,Stock",
            "OFFERS,zh,Ofertas",
            "MEMBER_PRICE,zh,Precio_de_socio",
            "NO_DISCOUNT,zh,Sin_descuento",
            "TOP_SALES,zh,Top_ventas"
    })
    void namesEachStockViewFromTheJobRequest(String view, String language, String prefix) throws Exception {
        var storeId = UUID.randomUUID();
        var service = new StockExcelExportService(metadataJdbc(storeId), mock(StockTopSalesService.class));
        var request = new StockExcelExportService.ExportRequest(view, null, null, null,
                null, null, null, null, null, null,
                "TOP_SALES".equals(view) ? "ranking" : "name", "asc", language,
                LocalDate.now().minusDays(1), LocalDate.now(), null, null, null,
                List.of(new StockExcelExportService.ExportColumn("name", "Nombre")));
        var job = service.create(storeId, "ADMIN", false, request);
        service.run(job.id());
        var file = service.file(job.id(), storeId, "ADMIN");
        try {
            assertThat(file.fileName()).isEqualTo(prefix + "_141126.xlsx");
        } finally {
            Files.deleteIfExists(file.path());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "es,Café 夏 2026",
            "en,Café 夏 2026",
            "zh,Café 夏 2026"
    })
    void selectedPromotionNamePreservesUnicodeAcrossLanguages(String language, String prefix)
            throws Exception {
        var storeId = UUID.randomUUID();
        var jdbc = metadataJdbc(storeId);
        var promotionId = UUID.randomUUID();
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<StockExcelExportService.PromotionExportDetails>>any(),
                eq(storeId), eq(promotionId))).thenReturn(List.of(promotionDetails("Café / 夏 2026")));
        var service = new StockExcelExportService(jdbc, mock(StockTopSalesService.class));
        var job = service.create(storeId, "ADMIN", false,
                promotionRequest("SELECTED", promotionId, language));
        service.run(job.id());
        var file = service.file(job.id(), storeId, "ADMIN");
        try {
            assertThat(file.fileName()).isEqualTo(prefix + "_011126-301126.xlsx");
        } finally {
            Files.deleteIfExists(file.path());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "es,Promociones",
            "en,Promociones",
            "zh,Promociones"
    })
    void activePromotionNameUsesExportDateAcrossLanguages(String language, String prefix) throws Exception {
        var storeId = UUID.randomUUID();
        var service = new StockExcelExportService(metadataJdbc(storeId), mock(StockTopSalesService.class));
        var job = service.create(storeId, "ADMIN", false,
                promotionRequest("ACTIVE", null, language));
        service.run(job.id());
        var file = service.file(job.id(), storeId, "ADMIN");
        try {
            assertThat(file.fileName()).isEqualTo(prefix + "_141126.xlsx");
        } finally {
            Files.deleteIfExists(file.path());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "es,Promocion",
            "en,Promocion",
            "zh,Promocion"
    })
    void selectedPromotionWithUnsafeNameUsesSafeFallback(String language, String fallback)
            throws Exception {
        var storeId = UUID.randomUUID();
        var jdbc = metadataJdbc(storeId);
        var promotionId = UUID.randomUUID();
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<StockExcelExportService.PromotionExportDetails>>any(),
                eq(storeId), eq(promotionId))).thenReturn(List.of(promotionDetails(" / ... : ")));
        var service = new StockExcelExportService(jdbc, mock(StockTopSalesService.class));
        var job = service.create(storeId, "ADMIN", false,
                promotionRequest("SELECTED", promotionId, language));
        service.run(job.id());
        var file = service.file(job.id(), storeId, "ADMIN");
        try {
            assertThat(file.fileName()).isEqualTo(fallback + "_011126-301126.xlsx");
        } finally {
            Files.deleteIfExists(file.path());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "2026-01-02,2026-02-03,020126-030226",
            "2026-01-02,2026-01-02,020126-020126",
            "2026-01-02,,020126-Sin_fin"
    })
    void selectedPromotionFileNameUsesStoredPeriodAndKeepsItsSnapshot(String start, String end, String period)
            throws Exception {
        var storeId = UUID.randomUUID();
        var promotionId = UUID.randomUUID();
        var jdbc = metadataJdbc(storeId);
        var result = mock(ResultSet.class);
        when(result.getString(1)).thenReturn("  Promoción de Verano 夏  ");
        when(result.getObject(2, LocalDate.class)).thenReturn(LocalDate.parse(start));
        when(result.getObject(3, LocalDate.class)).thenReturn(end == null ? null : LocalDate.parse(end));
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<StockExcelExportService.PromotionExportDetails>>any(),
                eq(storeId), eq(promotionId))).thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    assertThat(sql).contains("promotion.fecha_inicio", "promotion.fecha_fin",
                            "store.id = ? and promotion.id = ?");
                    RowMapper<StockExcelExportService.PromotionExportDetails> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(result, 0));
                });
        var service = new StockExcelExportService(jdbc, mock(StockTopSalesService.class));
        var job = service.create(storeId, "ADMIN", false, promotionRequest("SELECTED", promotionId));
        when(result.getString(1)).thenReturn("Nombre modificado después de exportar");
        when(result.getObject(3, LocalDate.class)).thenReturn(LocalDate.of(2027, 12, 31));
        service.run(job.id());
        var file = service.file(job.id(), storeId, "ADMIN");
        try {
            assertThat(file.fileName()).isEqualTo("Promoción de Verano 夏_" + period + ".xlsx");
        } finally {
            Files.deleteIfExists(file.path());
        }
    }

    private static StockExcelExportService.PromotionExportDetails promotionDetails(String name) {
        return new StockExcelExportService.PromotionExportDetails(name,
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
    }

    private static StockExcelExportService.ExportRequest promotionRequest(String scope, UUID promotionId) {
        return promotionRequest(scope, promotionId, "es");
    }

    private static JdbcTemplate metadataJdbc(UUID storeId) {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<StockExportLayout.Metadata>>any(),
                eq(storeId))).thenReturn(List.of(new StockExportLayout.Metadata(
                        "Empresa", "B12345678", "001 - Centro", "Dirección", null,
                        "Atlantic/Canary", Instant.parse("2026-11-14T12:00:00Z"))));
        return jdbc;
    }

    private static StockExcelExportService.ExportRequest promotionRequest(
            String scope, UUID promotionId, String language) {
        return new StockExcelExportService.ExportRequest("PROMOTIONS", null, null, null,
                null, null, null, null, null, null, "name", "asc", language,
                null, null, null, null, null,
                List.of(new StockExcelExportService.ExportColumn("name", "Nombre"),
                        new StockExcelExportService.ExportColumn("salePrice", "Precio")),
                scope, promotionId);
    }
}
