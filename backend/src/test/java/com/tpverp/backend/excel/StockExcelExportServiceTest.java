package com.tpverp.backend.excel;

import com.tpverp.backend.inventory.StockTopSalesService;
import com.tpverp.backend.inventory.StockTopSalesRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
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
        var service = new StockExcelExportService(mock(JdbcTemplate.class), topSales);
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
            assertThat(sheet.getLastRowNum()).isEqualTo(expectedCodes.size());
            for (int index = 0; index < expectedCodes.size(); index++) {
                var row = sheet.getRow(index + 1);
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

    @Test
    void selectedPromotionIsStoreScopedAndKeepsItsNameWhenTheJobCompletes() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var storeId = UUID.randomUUID();
        var promotionId = UUID.randomUUID();
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                eq(storeId), eq(promotionId))).thenReturn(List.of("Café / Verano 25%"));
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
        verify(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                eq(storeId), eq(promotionId));
        service.run(job.id());
        var file = service.file(job.id(), storeId, "ADMIN");
        try {
            assertThat(file.fileName()).isEqualTo("productos-promocion-cafe-verano-25-" + LocalDate.now() + ".xlsx");
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
        var jdbc = mock(JdbcTemplate.class);
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
        var storeId = UUID.randomUUID();
        var job = service.create(storeId, "ADMIN", false, promotionRequest(null, null));
        service.run(job.id());
        var file = service.file(job.id(), storeId, "ADMIN");
        try {
            assertThat(file.fileName()).isEqualTo("productos-promociones-activas-" + LocalDate.now() + ".xlsx");
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
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
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
        var jdbc = mock(JdbcTemplate.class);
        var service = new StockExcelExportService(jdbc, mock(StockTopSalesService.class));
        var storeId = UUID.randomUUID();
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
            "CURRENT,es,stock",
            "OFFERS,es,productos-con-oferta",
            "MEMBER_PRICE,es,productos-precio-miembro",
            "NO_DISCOUNT,es,productos-sin-descuento",
            "TOP_SALES,es,top-ventas",
            "CURRENT,en,stock",
            "OFFERS,en,products-on-offer",
            "MEMBER_PRICE,en,member-price-products",
            "NO_DISCOUNT,en,non-discountable-products",
            "TOP_SALES,en,top-sales",
            "CURRENT,zh,库存",
            "OFFERS,zh,优惠商品",
            "MEMBER_PRICE,zh,会员价商品",
            "NO_DISCOUNT,zh,不可折扣商品",
            "TOP_SALES,zh,热销商品"
    })
    void namesEachStockViewFromTheJobRequest(String view, String language, String prefix) throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var service = new StockExcelExportService(jdbc, mock(StockTopSalesService.class));
        var storeId = UUID.randomUUID();
        var request = new StockExcelExportService.ExportRequest(view, null, null, null,
                null, null, null, null, null, null,
                "TOP_SALES".equals(view) ? "ranking" : "name", "asc", language,
                LocalDate.now().minusDays(1), LocalDate.now(), null, null, null,
                List.of(new StockExcelExportService.ExportColumn("name", "Nombre")));
        var job = service.create(storeId, "ADMIN", false, request);
        service.run(job.id());
        var file = service.file(job.id(), storeId, "ADMIN");
        try {
            assertThat(file.fileName()).isEqualTo(prefix + "-" + LocalDate.now() + ".xlsx");
        } finally {
            Files.deleteIfExists(file.path());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "es,productos-promocion-cafe-夏-2026",
            "en,promotion-products-cafe-夏-2026",
            "zh,促销商品-cafe-夏-2026"
    })
    void selectedPromotionNamePreservesUnicodeAndUsesRequestLanguage(String language, String prefix)
            throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var storeId = UUID.randomUUID();
        var promotionId = UUID.randomUUID();
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                eq(storeId), eq(promotionId))).thenReturn(List.of("Café / 夏 2026"));
        var service = new StockExcelExportService(jdbc, mock(StockTopSalesService.class));
        var job = service.create(storeId, "ADMIN", false,
                promotionRequest("SELECTED", promotionId, language));
        service.run(job.id());
        var file = service.file(job.id(), storeId, "ADMIN");
        try {
            assertThat(file.fileName()).isEqualTo(prefix + "-" + LocalDate.now() + ".xlsx");
        } finally {
            Files.deleteIfExists(file.path());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "es,productos-promociones-activas",
            "en,active-promotions-products",
            "zh,有效促销商品"
    })
    void activePromotionNameUsesRequestLanguage(String language, String prefix) throws Exception {
        var service = new StockExcelExportService(mock(JdbcTemplate.class), mock(StockTopSalesService.class));
        var storeId = UUID.randomUUID();
        var job = service.create(storeId, "ADMIN", false,
                promotionRequest("ACTIVE", null, language));
        service.run(job.id());
        var file = service.file(job.id(), storeId, "ADMIN");
        try {
            assertThat(file.fileName()).isEqualTo(prefix + "-" + LocalDate.now() + ".xlsx");
        } finally {
            Files.deleteIfExists(file.path());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "es,seleccionada",
            "en,selected",
            "zh,已选促销"
    })
    void selectedPromotionWithUnsafeNameUsesLocalizedFallback(String language, String fallback)
            throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var storeId = UUID.randomUUID();
        var promotionId = UUID.randomUUID();
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                eq(storeId), eq(promotionId))).thenReturn(List.of(" / ✨ "));
        var service = new StockExcelExportService(jdbc, mock(StockTopSalesService.class));
        var job = service.create(storeId, "ADMIN", false,
                promotionRequest("SELECTED", promotionId, language));
        service.run(job.id());
        var file = service.file(job.id(), storeId, "ADMIN");
        try {
            String prefix = "es".equals(language) ? "productos-promocion"
                    : "en".equals(language) ? "promotion-products" : "促销商品";
            assertThat(file.fileName()).isEqualTo(prefix + "-" + fallback + "-" + LocalDate.now() + ".xlsx");
        } finally {
            Files.deleteIfExists(file.path());
        }
    }

    private static StockExcelExportService.ExportRequest promotionRequest(String scope, UUID promotionId) {
        return promotionRequest(scope, promotionId, "es");
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
