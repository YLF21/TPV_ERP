package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.inventory.StockTopSalesRow;
import com.tpverp.backend.inventory.StockTopSalesService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class StockPdfExportTest {

    @Test
    void pdfUsesGridOrderAndReadsEveryPage() throws Exception {
        var storeId = UUID.randomUUID();
        var sales = mock(StockTopSalesService.class);
        when(sales.topSales(storeId, FROM, TO, null)).thenReturn(IntStream.rangeClosed(1, 601)
                .mapToObj(index -> row(String.format("ITEM-%03d", index), "Café " + index))
                .toList());
        var service = new StockExcelExportService(mock(JdbcTemplate.class), sales);
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
        var service = new StockExcelExportService(mock(JdbcTemplate.class), sales);
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
        var service = new StockExcelExportService(mock(JdbcTemplate.class), mock(StockTopSalesService.class));
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
        var service = new StockExcelExportService(mock(JdbcTemplate.class), mock(StockTopSalesService.class));
        var request = new StockExcelExportService.ExportRequest("CURRENT_STOCK", null, null, null,
                null, null, null, null, null, null, "name", "asc", "es",
                null, null, null, null, null,
                List.of(new StockExcelExportService.ExportColumn("name", "Nombre"),
                        new StockExcelExportService.ExportColumn("purchasePrice", "Precio compra")));
        var job = service.createPdf(storeId, "USER", false, request);
        service.run(job.id());
        var file = service.file(job.id(), storeId, "USER");
        try (var pdf = Loader.loadPDF(file.path().toFile())) {
            assertThat(new PDFTextStripper().getText(pdf)).contains("Nombre", "Sin datos")
                    .doesNotContain("Precio compra");
        } finally {
            Files.deleteIfExists(file.path());
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
        var service = new StockExcelExportService(mock(JdbcTemplate.class), sales);
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
}
