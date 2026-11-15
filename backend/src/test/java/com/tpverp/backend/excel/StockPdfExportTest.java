package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import com.tpverp.backend.inventory.StockTopSalesRow;
import com.tpverp.backend.inventory.StockTopSalesService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Instant;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class StockPdfExportTest {

    @Test
    void horizontalPagesKeepOriginalColumnOrderAndIdenticalRowPositions() throws Exception {
        var columns = new java.util.ArrayList<StockExcelExportService.ExportColumn>();
        columns.add(new StockExcelExportService.ExportColumn("code", "LEFT CODE"));
        columns.add(new StockExcelExportService.ExportColumn("name", "LEFT NAME"));
        for (int i = 1; i <= 6; i++) {
            columns.add(new StockExcelExportService.ExportColumn("custom" + i, "MIDDLE " + i));
        }
        columns.add(new StockExcelExportService.ExportColumn("supplier", "RIGHT SUPPLIER"));
        var request = new StockExcelExportService.ExportRequest("CURRENT_STOCK", null, null, null,
                null, null, null, null, null, null, "name", "asc", "en",
                null, null, null, null, null, columns);
        var source = Files.createTempFile("stock-pdf-fixture", ".xlsx");
        var output = Files.createTempFile("stock-pdf-alignment", ".pdf");
        try {
            try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
                var sheet = workbook.createSheet();
                var header = sheet.createRow(8);
                for (int i = 0; i < columns.size(); i++) {
                    header.createCell(i).setCellValue(columns.get(i).label());
                }
                for (int r = 0; r < 3; r++) {
                    var row = sheet.createRow(9 + r);
                    row.createCell(0).setCellValue("L" + (r + 1));
                    row.createCell(1).setCellValue(r == 0
                            ? "FIRST_SEGMENT " + "wide ".repeat(110) + " LAST_SEGMENT" : "Short name");
                    for (int c = 2; c < 8; c++) row.createCell(c).setCellValue("M" + c + "-" + r);
                    row.createCell(8).setCellValue("R" + (r + 1) + " "
                            + (r == 1 ? "right ".repeat(180) : r == 2
                                    ? "oversize ".repeat(3500) + "FINAL_TAIL" : "short"));
                }
                sheet.createRow(12).createCell(0).setCellValue("TOTAL");
                try (var stream = Files.newOutputStream(source)) { workbook.write(stream); }
            }
            StockPdfRenderer.render(source, output, request,
                    new StockExportLayout.Metadata("One Store", "TAX", "001", "Main Street",
                            null, "UTC", Instant.parse("2026-11-14T12:00:00Z")), 3);
            var designDirectory = Path.of("target", "stock-export-design");
            Files.createDirectories(designDirectory);
            Files.copy(output, designDirectory.resolve("horizontal-continuation-alignment.pdf"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try (var pdf = Loader.loadPDF(output.toFile())) {
                assertThat(pdf.getNumberOfPages()).isGreaterThan(2);
                var first = pageText(pdf, 1);
                var second = pageText(pdf, 2);
                assertThat(first).contains("LEFT CODE", "LEFT NAME", "L1", "L2")
                        .doesNotContain("RIGHT SUPPLIER", "R1");
                assertThat(second).contains("RIGHT SUPPLIER", "R1", "R2")
                        .doesNotContain("LEFT CODE", "L1", "One Store");
                assertThat(first.indexOf("L1")).isLessThan(first.indexOf("L2"));
                assertThat(second.indexOf("R1")).isLessThan(second.indexOf("R2"));
                var firstY = markerY(pdf, 1, "L1", "L2");
                var secondY = markerY(pdf, 2, "R1", "R2");
                assertThat(secondY.get("R1")).isCloseTo(firstY.get("L1"),
                        org.assertj.core.data.Offset.offset(0.5f));
                assertThat(secondY.get("R2")).isCloseTo(firstY.get("L2"),
                        org.assertj.core.data.Offset.offset(0.5f));
                assertThat(new PDFTextStripper().getText(pdf)).contains("FINAL_TAIL");
                assertThat(pageText(pdf, 3)).contains("L3");
                assertThat(pageText(pdf, 4)).contains("R3").doesNotContain("FINAL_TAIL");
                StringBuilder continuation = new StringBuilder();
                for (int page = 5; page <= pdf.getNumberOfPages(); page++) {
                    continuation.append(pageText(pdf, page));
                }
                assertThat(continuation.toString()).contains("FINAL_TAIL").doesNotContain("L3", "R3");
                for (int page = 0; page < pdf.getNumberOfPages(); page++) {
                    assertThat(effectiveWidth(pdf.getPage(page))).isEqualTo(842f);
                }
            }
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(output);
        }
    }

    private static String pageText(org.apache.pdfbox.pdmodel.PDDocument pdf, int page)
            throws IOException {
        var stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(pdf);
    }

    private static float effectiveWidth(org.apache.pdfbox.pdmodel.PDPage page) {
        return Math.floorMod(page.getRotation(), 180) == 90
                ? page.getMediaBox().getHeight() : page.getMediaBox().getWidth();
    }

    private static Map<String, Float> markerY(org.apache.pdfbox.pdmodel.PDDocument pdf,
            int page, String... markers) throws IOException {
        var found = new HashMap<String, Float>();
        var stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> positions) throws IOException {
                for (String marker : markers) {
                    if (text.contains(marker) && !positions.isEmpty()) {
                        found.putIfAbsent(marker, positions.get(0).getYDirAdj());
                    }
                }
                super.writeString(text, positions);
            }
        };
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        stripper.getText(pdf);
        assertThat(found).containsKeys(markers);
        return found;
    }

    @Test
    void pdfUsesGridOrderAndReadsEveryPage() throws Exception {
        var storeId = UUID.randomUUID();
        var sales = mock(StockTopSalesService.class);
        when(sales.topSales(storeId, FROM, TO, null)).thenReturn(IntStream.rangeClosed(1, 601)
                .mapToObj(index -> row(String.format("ITEM-%03d", index), "Café " + index))
                .toList());
        var service = new StockExcelExportService(metadataJdbc(storeId), sales);
        var job = service.createPdf(storeId, "ADMIN", false, request("es"));
        service.run(job.id());
        assertThat(service.status(job.id(), storeId, "ADMIN").error()).isNull();

        assertThat(service.status(job.id(), storeId, "ADMIN").processedRows()).isEqualTo(601);
        var file = service.file(job.id(), storeId, "ADMIN");
        try {
            assertThat(file.fileName()).endsWith(".pdf");
            assertThat(Files.readAllBytes(file.path())).startsWith("%PDF".getBytes());
            try (var pdf = Loader.loadPDF(file.path().toFile())) {
                assertThat(pdf.getNumberOfPages()).isGreaterThan(1);
                var text = new PDFTextStripper().getText(pdf);
                assertThat(text).contains("Café 1", "ITEM-001", "ITEM-601", "Café 601");
                assertThat(text.indexOf("Nombre")).isLessThan(text.indexOf("Código"));
            }
        } finally {
            Files.deleteIfExists(file.path());
        }
    }

    @Test
    void chineseContentAndTitleAreExtractable() throws Exception {
        var storeId = UUID.randomUUID();
        var sales = mock(StockTopSalesService.class);
        when(sales.topSales(storeId, FROM, TO, null)).thenReturn(List.of(row("CN-1", "Café 海洋 1,67 €")));
        var service = new StockExcelExportService(metadataJdbc(storeId), sales);
        var job = service.createPdf(storeId, "ADMIN", true, request("zh"));
        service.run(job.id());
        assertThat(service.status(job.id(), storeId, "ADMIN").error()).isNull();

        var file = service.file(job.id(), storeId, "ADMIN");
        try (var pdf = Loader.loadPDF(file.path().toFile())) {
            assertThat(new PDFTextStripper().getText(pdf)).contains("热销商品", "名称", "Café 海洋 1,67 €");
        } finally {
            Files.deleteIfExists(file.path());
        }
    }

    @Test
    void pdfKeepsOwnerStoreIsolationAndRejectsPromotions() throws Exception {
        var storeId = UUID.randomUUID();
        var service = new StockExcelExportService(metadataJdbc(storeId), mock(StockTopSalesService.class));
        var job = service.createPdf(storeId, "ADMIN", false, request("en"));
        assertThatThrownBy(() -> service.status(job.id(), UUID.randomUUID(), "ADMIN"))
                .hasMessage("stock_excel_export_not_found");
        assertThatThrownBy(() -> service.file(job.id(), storeId, "OTHER"))
                .hasMessage("stock_excel_export_not_found");
        assertThatThrownBy(() -> service.createPdf(storeId, "OTHER", false,
                new StockExcelExportService.ExportRequest("PROMOTIONS", null, null, null,
                        null, null, null, null, null, null, "name", "asc", "es",
                        null, null, null, null, null,
                        List.of(new StockExcelExportService.ExportColumn("name", "Nombre")))))
                .hasMessage("stock_pdf_export_view_invalid");
    }

    @Test
    void pdfRemovesPurchaseFieldsForUsersWithoutProductManagement() throws Exception {
        var storeId = UUID.randomUUID();
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<StockExportLayout.Metadata>>any(),
                eq(storeId))).thenReturn(List.of(new StockExportLayout.Metadata(
                        "Empresa Canaria", "B12345678", "001 - Centro", "Calle Uno 1",
                        null, "Atlantic/Canary", Instant.parse("2026-11-14T10:30:00Z"))));
        var service = new StockExcelExportService(jdbc, mock(StockTopSalesService.class));
        var request = new StockExcelExportService.ExportRequest("CURRENT_STOCK", null, null, null,
                null, null, null, null, null, null, "name", "asc", "es",
                null, null, null, null, null,
                List.of(new StockExcelExportService.ExportColumn("name", "Nombre"),
                        new StockExcelExportService.ExportColumn("purchasePrice", "Precio compra")));
        var job = service.createPdf(storeId, "USER", false, request);
        service.run(job.id());
        var file = service.file(job.id(), storeId, "USER");
        var designDirectory = Path.of("target", "stock-export-design");
        Files.createDirectories(designDirectory);
        Files.copy(file.path(), designDirectory.resolve("current-stock-empty.pdf"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (var pdf = Loader.loadPDF(file.path().toFile())) {
            assertThat(new PDFTextStripper().getText(pdf)).contains("Nombre", "Sin datos",
                    "Empresa Canaria", "001 - Centro", "TOTAL", "PRODUCTOS")
                    .doesNotContain("Precio compra");
            assertThat(effectiveWidth(pdf.getPage(0))).isEqualTo(842.0f);
        } finally {
            Files.deleteIfExists(file.path());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "TOP_SALES,INFORME DE TOP VENTAS,REGISTROS",
            "OFFERS,INFORME DE OFERTAS,PRODUCTOS",
            "MEMBER_PRICE,INFORME DE PRECIO DE SOCIO,PRODUCTOS",
            "NO_DISCOUNT,INFORME DE PRODUCTOS SIN DESCUENTO,PRODUCTOS"
    })
    void otherStockViewsUseStoreHeaderAndA4(String view, String title, String countLabel) throws Exception {
        var storeId = UUID.randomUUID();
        var service = new StockExcelExportService(metadataJdbc(storeId), mock(StockTopSalesService.class));
        var request = new StockExcelExportService.ExportRequest(view, null, null, null,
                null, null, null, null, null, null,
                "TOP_SALES".equals(view) ? "ranking" : "name", "asc", "es",
                FROM, TO, null, null, null,
                List.of(new StockExcelExportService.ExportColumn("name", "Nombre"),
                        new StockExcelExportService.ExportColumn("code", "Código")));
        var job = service.createPdf(storeId, "ADMIN", false, request);
        service.run(job.id());
        assertThat(service.status(job.id(), storeId, "ADMIN").error()).isNull();
        var file = service.file(job.id(), storeId, "ADMIN");
        var designDirectory = Path.of("target", "stock-export-design");
        Files.createDirectories(designDirectory);
        Files.copy(file.path(), designDirectory.resolve(view.toLowerCase(java.util.Locale.ROOT) + "-empty.pdf"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (var pdf = Loader.loadPDF(file.path().toFile())) {
            assertThat(effectiveWidth(pdf.getPage(0))).isEqualTo(842f);
            var text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains(title, "Empresa", "001 - Centro", "Sin datos", countLabel, "TOTAL");
            if ("TOP_SALES".equals(view)) assertThat(text).contains("01/08/2026", "17/08/2026");
        } finally {
            Files.deleteIfExists(file.path());
        }
    }

    @Test
    void currentStockDefaultColumnsUseA4ContinuationsAndStreamEveryRow() throws Exception {
        var storeId = UUID.randomUUID();
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<StockExportLayout.Metadata>>any(),
                eq(storeId))).thenReturn(List.of(new StockExportLayout.Metadata(
                        "Empresa Canaria", "B12345678", "001 - Centro", "Calle Uno 1",
                        null, "Atlantic/Canary", Instant.parse("2026-11-14T10:30:00Z"))));
        var result = mock(ResultSet.class);
        var position = new AtomicInteger();
        when(result.next()).thenAnswer(invocation -> position.incrementAndGet() <= 180);
        when(result.getString("name")).thenAnswer(invocation -> "Producto 海洋 Café " + position.get()
                + "\nSegunda línea completa");
        when(result.getString("code")).thenAnswer(invocation -> "P-" + position.get());
        when(result.getBigDecimal("local_stock")).thenAnswer(invocation -> position.get() == 1
                ? new BigDecimal("108") : new BigDecimal("1.125"));
        when(result.getBigDecimal("total_stock")).thenAnswer(invocation -> position.get() == 1
                ? new BigDecimal("193") : new BigDecimal("2.250"));
        when(result.getBigDecimal("sale_price")).thenReturn(new BigDecimal("1.675"));
        when(jdbc.query(org.mockito.ArgumentMatchers.any(PreparedStatementCreator.class),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<Object>>any()))
                .thenAnswer(invocation -> {
                    ResultSetExtractor<?> extractor = invocation.getArgument(1);
                    return extractor.extractData(result);
                });
        String[] keys = {"code", "barcode", "name", "type", "discount", "supplier", "family",
                "subfamily", "tax", "taxIncluded", "packageQuantity", "purchasePrice", "salePrice",
                "memberPrice", "wholesalePrice", "offerPrice", "offerActive", "offerFrom", "offerUntil",
                "warehouse", "localStock", "totalStock", "stockMin", "stockMax", "status"};
        String[] labels = {"Código", "Código de barras", "Nombre", "Tipo", "Usar precio", "Proveedor",
                "Familia", "Subfamilia", "Impuesto", "IVA incl.", "Cantidad por paquete",
                "Precio compra", "Precio venta", "Precio de miembro", "Precio mayor", "Precio oferta",
                "Oferta activa", "Oferta desde", "Oferta hasta", "Almacén", "Stock local", "Stock total",
                "Stock min", "Stock max", "Estado"};
        var columns = IntStream.range(0, keys.length)
                .mapToObj(index -> new StockExcelExportService.ExportColumn(keys[index], labels[index]))
                .toList();
        var request = new StockExcelExportService.ExportRequest("CURRENT_STOCK", null, null, null,
                null, null, null, null, null, null, "name", "asc", "es",
                null, null, null, null, null, columns);
        var service = new StockExcelExportService(jdbc, mock(StockTopSalesService.class));
        var job = service.createPdf(storeId, "ADMIN", true, request);
        service.run(job.id());
        assertThat(service.status(job.id(), storeId, "ADMIN").error()).isNull();
        assertThat(service.status(job.id(), storeId, "ADMIN").processedRows()).isEqualTo(180);
        verify(jdbc, times(1)).query(org.mockito.ArgumentMatchers.any(PreparedStatementCreator.class),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<Object>>any());
        var file = service.file(job.id(), storeId, "ADMIN");
        var designDirectory = Path.of("target", "stock-export-design");
        Files.createDirectories(designDirectory);
        Files.copy(file.path(), designDirectory.resolve("current-stock-25-columns.pdf"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (var pdf = Loader.loadPDF(file.path().toFile())) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(1);
            for (int pageNumber = 1; pageNumber <= pdf.getNumberOfPages(); pageNumber++) {
                var page = pdf.getPage(pageNumber - 1);
                assertThat(List.of(page.getMediaBox().getWidth(), page.getMediaBox().getHeight()))
                        .containsExactlyInAnyOrder(595f, 842f);
                int rotation = Math.floorMod(page.getRotation(), 180);
                float effectiveWidth = rotation == 90
                        ? page.getMediaBox().getHeight() : page.getMediaBox().getWidth();
                assertThat(effectiveWidth).isLessThanOrEqualTo(842f);
                var pageText = new PDFTextStripper();
                pageText.setStartPage(pageNumber);
                pageText.setEndPage(pageNumber);
                assertThat(pageText.getText(pdf)).contains("Página " + pageNumber);
            }
            var text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("P-1", "P-180", "Segunda línea completa", "Stock local",
                    "Stock total", "108", "193", "1,125", "1,675 €", "Página 1 de");
            assertThat(text).doesNotContain("es-ES", "[$");
            assertThat(text).doesNotContain("108,\n", "193,\n");
            assertThat(text).contains("TOTAL");
            assertThat(text.split("Empresa Canaria", -1).length - 1).isOne();
            assertThat(text.split("P-180", -1).length - 1).isOne();
        } finally {
            Files.deleteIfExists(file.path());
        }

        position.set(0);
        var wideXlsx = service.create(storeId, "ADMIN", true, request);
        service.run(wideXlsx.id());
        assertThat(service.status(wideXlsx.id(), storeId, "ADMIN").error()).isNull();
        var xlsxFile = service.file(wideXlsx.id(), storeId, "ADMIN");
        Files.copy(xlsxFile.path(), designDirectory.resolve("current-stock-25-columns.xlsx"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                Files.newInputStream(xlsxFile.path()))) {
            assertThat(workbook.getNumberOfSheets()).isOne();
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getPrintSetup().getPaperSize())
                    .isEqualTo(org.apache.poi.ss.usermodel.PrintSetup.A4_PAPERSIZE);
            assertThat(sheet.getRow(9).getCell(0).getStringCellValue()).isEqualTo("P-1");
            assertThat(sheet.getRow(188).getCell(0).getStringCellValue()).isEqualTo("P-180");
            for (int c = 0; c < labels.length; c++) {
                assertThat(sheet.getRow(8).getCell(c).getStringCellValue()).isEqualTo(labels[c]);
            }
        }
        Files.deleteIfExists(xlsxFile.path());

        position.set(0);
        var narrowColumns = List.of(columns.get(0), columns.get(2), columns.get(12),
                columns.get(20), columns.get(21), columns.get(24));
        var narrowRequest = new StockExcelExportService.ExportRequest("CURRENT_STOCK", null, null, null,
                null, null, null, null, null, null, "name", "asc", "es",
                null, null, null, null, null, narrowColumns);
        var narrowJob = service.createPdf(storeId, "ADMIN", true, narrowRequest);
        service.run(narrowJob.id());
        assertThat(service.status(narrowJob.id(), storeId, "ADMIN").error()).isNull();
        var narrowFile = service.file(narrowJob.id(), storeId, "ADMIN");
        Files.copy(narrowFile.path(), designDirectory.resolve("current-stock-narrow-a4.pdf"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (var pdf = Loader.loadPDF(narrowFile.path().toFile())) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(1);
            assertThat(effectiveWidth(pdf.getPage(0))).isEqualTo(842f);
            var firstPage = new PDFTextStripper();
            firstPage.setStartPage(1);
            firstPage.setEndPage(1);
            assertThat(firstPage.getText(pdf)).contains("Empresa Canaria", "Stock local", "Página 1 de");
            var secondPage = new PDFTextStripper();
            secondPage.setStartPage(2);
            secondPage.setEndPage(2);
            assertThat(secondPage.getText(pdf)).contains("Stock local", "Página 2 de")
                    .doesNotContain("Empresa Canaria");
            assertThat(new PDFTextStripper().getText(pdf)).contains("P-180");
        } finally {
            Files.deleteIfExists(narrowFile.path());
        }
    }

    @Test
    void controllerReturnsPdfMimeAndKeepsXlsxDefault() throws Exception {
        var storeId = UUID.randomUUID();
        var store = mock(Store.class);
        when(store.getId()).thenReturn(storeId);
        var organization = mock(CurrentOrganization.class);
        when(organization.currentStore()).thenReturn(store);
        var sales = mock(StockTopSalesService.class);
        when(sales.topSales(storeId, FROM, TO, null)).thenReturn(List.of(row("A", "Apple")));
        var service = new StockExcelExportService(metadataJdbc(storeId), sales);
        var controller = new StockExcelExportController(service,
                mock(StockExcelExportJobLauncher.class), organization);
        var auth = UsernamePasswordAuthenticationToken.authenticated("ADMIN", "token", List.of());
        for (boolean pdf : List.of(true, false)) {
            var job = pdf ? service.createPdf(storeId, "ADMIN", false, request("en"))
                    : service.create(storeId, "ADMIN", false, request("en"));
            service.run(job.id());
            assertThat(service.status(job.id(), storeId, "ADMIN").error()).isNull();
            var response = controller.file(job.id(), auth);
            try {
                assertThat(response.getHeaders().getContentType().toString()).isEqualTo(pdf
                        ? "application/pdf"
                        : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                assertThat(response.getHeaders().getContentDisposition().getFilename())
                        .endsWith(pdf ? ".pdf" : ".xlsx");
            } finally {
                Files.deleteIfExists(service.file(job.id(), storeId, "ADMIN").path());
            }
        }
    }

    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 17);

    private static StockExcelExportService.ExportRequest request(String language) {
        return new StockExcelExportService.ExportRequest("TOP_SALES", null, null, null,
                null, null, null, null, null, null, "ranking", "asc", language,
                FROM, TO, null, null, null,
                List.of(new StockExcelExportService.ExportColumn("name",
                                "zh".equals(language) ? "名称" : "Nombre"),
                        new StockExcelExportService.ExportColumn("code", "Código")));
    }

    private static StockTopSalesRow row(String code, String name) {
        return new StockTopSalesRow(UUID.randomUUID(), code, "", name, null,
                "GENERAL", null, "-", List.of(), BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, UUID.randomUUID(), "GENERAL");
    }

    private static JdbcTemplate metadataJdbc(UUID storeId) {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<StockExportLayout.Metadata>>any(),
                eq(storeId))).thenReturn(List.of(new StockExportLayout.Metadata(
                        "Empresa", "B12345678", "001 - Centro", "Dirección", null,
                        "Atlantic/Canary", Instant.parse("2026-11-14T12:00:00Z"))));
        return jdbc;
    }
}
