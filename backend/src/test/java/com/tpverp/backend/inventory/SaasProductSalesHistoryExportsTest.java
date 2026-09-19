package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.tpverp.backend.inventory.SaasProductSalesHistoryTestData.*;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.document.template.SafeJrxmlCompiler;
import com.tpverp.backend.document.template.SaasProductSalesHistoryJasperRenderer;
import com.tpverp.backend.excel.StockSalesHistoryExportRequest.Column;
import com.tpverp.backend.excel.StockSalesHistoryExportRequest.Labels;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SaasProductSalesHistoryExportsTest {
    final CurrentOrganization organization = mock(CurrentOrganization.class);
    final Product product = mock(Product.class);
    final SaasProductSalesHistoryExports exports = new SaasProductSalesHistoryExports(organization,
            new SaasProductSalesHistoryJasperRenderer(new SafeJrxmlCompiler()));
    @BeforeEach void prepare() {
        var company = mock(Company.class); var store = mock(Store.class);
        when(organization.currentCompany()).thenReturn(company); when(organization.currentStore()).thenReturn(store);
        when(company.getRazonSocial()).thenReturn("EMPRESA FICTICIA"); when(store.getTimezone()).thenReturn("Atlantic/Canary");
        when(product.getCode()).thenReturn("00042"); when(product.getName()).thenReturn("Artículo ficticio con nombre largo para revisión");
    }
    @Test void xlsxIsNeutralPreservesPrecisionAndNeverInterpretsUserTextAsFormula() throws Exception {
        var data = response(); var first = (com.fasterxml.jackson.databind.node.ObjectNode) data.path("items").get(0);
        first.put("customerName", "=SUM(A1:A99)"); first.put("lineTotal", "12345678901234567.89");
        var request = request("detail", "es", List.of(new Column("customer", "Cliente"), new Column("quantity", "Cantidad"), new Column("unitPrice", "Precio"), new Column("total", "Total")));
        var bytes = exports.excel(product, request, data);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(4).getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(4).getCell(0).getStringCellValue()).isEqualTo("=SUM(A1:A99)");
            assertThat(sheet.getRow(4).getCell(2).getNumericCellValue()).isEqualTo(1.875);
            assertThat(sheet.getRow(4).getCell(3).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(4).getCell(3).getStringCellValue()).isEqualTo("12345678901234567.89");
            assertThat(sheet.getRow(3).getCell(4).getStringCellValue()).isEqualTo("Moneda");
            for (var row : sheet) for (var cell : row) assertThat(cell.getCellStyle().getFillPattern()).isEqualTo(FillPatternType.NO_FILL);
        }
        save("saas-historial.xlsx", bytes);
    }
    @Test void comparisonExportPreservesNumericSortingAndSeparatesCurrencies() throws Exception {
        var data = response(); var second = data.withArray("comparison").addObject();
        second.put("storeId", java.util.UUID.randomUUID().toString()); second.put("storeName", "Tienda con 10 unidades"); second.put("storeCode", "002");
        second.put("currency", "USD"); second.put("quantitySold", "10"); second.put("quantityReturned", "0"); second.put("netQuantity", "10"); second.put("netAmount", "20");
        var usd = data.withArray("totals").addObject(); usd.put("currency", "USD"); usd.put("quantitySold", "10"); usd.put("quantityReturned", "0"); usd.put("netQuantity", "10"); usd.put("netAmount", "20");
        var request = request("comparison", "es", List.of(new Column("store", "Tienda"), new Column("quantitySold", "Vendida"), new Column("quantityReturned", "Devuelta"), new Column("netQuantity", "Cantidad neta"), new Column("netAmount", "Importe")));
        byte[] bytes = exports.excel(product, request, data);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(4).getCell(0).getStringCellValue()).isEqualTo("Tienda con 10 unidades");
            assertThat(sheet.getRow(4).getCell(3).getNumericCellValue()).isEqualTo(10);
            assertThat(sheet.getRow(7).getCell(0).getStringCellValue()).contains("EUR", "USD");
        }
        var pdf = pdf(request, data); assertPdf(pdf, "Tienda con 10 unidades", "Cantidad neta", "EUR", "USD"); save("saas-comparacion.pdf", pdf);
    }
    @Test void detailPdfRepeatsHeadersAndPreservesCompleteRowsAcrossPages() throws Exception {
        var data = response(); data.withArray("items").removeAll();
        for (int i = 1; i <= 65; i++) data.withArray("items").add(row(i));
        var total = (com.fasterxml.jackson.databind.node.ObjectNode) data.path("totals").get(0); total.put("quantitySold", "130"); total.put("netQuantity", "130"); total.put("netAmount", "243.75");
        var request = request("detail", "es", List.of(new Column("occurredAt", "Fecha"),new Column("document", "Documento"), new Column("customer", "Cliente"),new Column("quantity", "Cantidad"),new Column("unitPrice", "Precio"),new Column("total", "Total"),new Column("store", "Tienda")));
        byte[] bytes = pdf(request, data);
        try (var document = Loader.loadPDF(bytes)) {
            assertThat(document.getNumberOfPages()).isGreaterThan(2);
            assertThat(new PDFTextStripper().getText(document).replaceAll("\\s+", " ")).contains("T-001-65", "1,875", "243,75", "Atlantic/Canary");
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                var text = new PDFTextStripper(); text.setStartPage(page); text.setEndPage(page);
                assertThat(text.getText(document)).contains("Documento", "Cliente", "Moneda", "Página " + page);
            }
        }
        save("saas-historial.pdf", bytes);
    }
    @Test void emptyAndChinesePdfRemainReadable() throws Exception {
        var data = response(); data.withArray("items").removeAll(); data.withArray("totals").removeAll(); data.withArray("comparison").removeAll();
        byte[] empty = pdf(request("detail", "es", List.of(new Column("document", "Documento"))), data);
        assertPdf(empty, "Sin movimientos", "Moneda", "00042"); save("saas-vacio.pdf", empty);
        byte[] chinese = pdf(request("comparison", "zh", List.of(new Column("store", "门店"), new Column("netQuantity", "净数量"))), response());
        assertPdf(chinese, "门店", "净数量", "币种"); save("saas-comparacion-zh.pdf", chinese);
    }
    @Test void rejectsColumnsFromTheOtherViewAndDuplicateLabels() {
        assertThatThrownBy(() -> exports.validate(request("comparison", "es", List.of(new Column("customer", "Cliente")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> exports.validate(request("detail", "es", List.of(new Column("total", "Total"), new Column("total", "Total 2")))))
                .isInstanceOf(IllegalArgumentException.class);
    }
    private byte[] pdf(SaasProductSalesHistoryApi.ExportRequest request, com.fasterxml.jackson.databind.JsonNode data) {
        return Base64.getDecoder().decode(exports.pdf(product, request, data).renderedPdf().base64());
    }
    private static SaasProductSalesHistoryApi.ExportRequest request(String view, String locale, List<Column> columns) {
        return new SaasProductSalesHistoryApi.ExportRequest(LocalDate.parse("2026-09-01"),LocalDate.parse("2026-09-19"),null,List.of(),"occurredAt","desc", view, columns,
                new Labels("Historial de ventas", "Producto", "Código", "Período", "Estado", "Todos", "Cantidad neta", "Importe neto"),locale,"netQuantity","desc");
    }
    private static void assertPdf(byte[] bytes, String... parts) throws Exception {
        try (var document = Loader.loadPDF(bytes)) { assertThat(new PDFTextStripper().getText(document).replaceAll("\\s+", " ")).contains(parts); }
    }
    private static void save(String name, byte[] bytes) throws Exception {
        if (Boolean.getBoolean("tpv.history.export.fixtures")) {
            var directory = Path.of("target", "saas-product-history-review"); Files.createDirectories(directory); Files.write(directory.resolve(name), bytes);
        }
    }
}
