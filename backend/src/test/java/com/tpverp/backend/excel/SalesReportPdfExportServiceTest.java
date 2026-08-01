package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class SalesReportPdfExportServiceTest {

    @Test
    void formatsCurrencyWithoutExposingTheExcelLocaleToken() throws Exception {
        var excel = mock(SalesReportExcelExportService.class);
        var authentication = mock(Authentication.class);
        when(excel.export(any(), any())).thenReturn(workbookWithCurrency());
        var service = new SalesReportPdfExportService(excel);
        var request = new SalesReportExportRequest(
                "salesReport.invoices",
                new SalesReportExportRequest.Filters(null, null, null, null, null, null, null, null, null),
                "",
                List.of(
                        new SalesReportExportRequest.Column("pending", "Pendiente"),
                        new SalesReportExportRequest.Column("total", "Total")));

        byte[] pdf = service.export(request, authentication);

        try (var document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text)
                    .contains("-12,10")
                    .contains("12,10")
                    .doesNotContain("es-ES")
                    .doesNotContain("[$");
        }
    }

    private byte[] workbookWithCurrency() throws Exception {
        try (var workbook = new XSSFWorkbook();
             var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Informe");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Pendiente");
            header.createCell(1).setCellValue("Total");
            var currency = workbook.createCellStyle();
            currency.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00 [$€-es-ES]"));
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue(-12.10);
            row.createCell(1).setCellValue(12.10);
            row.getCell(0).setCellStyle(currency);
            row.getCell(1).setCellStyle(currency);
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
