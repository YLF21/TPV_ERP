package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class SalesReportPdfExportServiceTest {

    @Test
    void warehouseListsUseStyledTablesPreservingValuesAndFirstPageMetadata() throws Exception {
        for (String key : List.of("salesReport.inputWarehouse", "salesReport.warehouseOutputs")) {
            boolean input = key.endsWith("inputWarehouse");
            String numberKey = input ? "input" : "output";
            String title = input ? "Entradas de almacén" : "Salidas de almacén";
            var columns = List.of(new SalesReportExportRequest.Column("date", "Fecha"),
                    new SalesReportExportRequest.Column(numberKey, "Documento"),
                    new SalesReportExportRequest.Column("warehouse", "Almacén"),
                    new SalesReportExportRequest.Column("productCount", "Cantidad"),
                    new SalesReportExportRequest.Column("comment", "Comentario"),
                    new SalesReportExportRequest.Column("total", "Total"));
            var request = new SalesReportExportRequest(key, null, "", columns);
            var company = mock(Company.class);
            when(company.getRazonSocial()).thenReturn("Empresa Demo");
            when(company.getTaxId()).thenReturn("B12345678");
            var store = mock(Store.class);
            when(store.getCodigoTienda()).thenReturn("001");
            when(store.getNombreEfectivo()).thenReturn("Tienda Demo");
            when(store.getTimezone()).thenReturn("Atlantic/Canary");
            var organization = mock(CurrentOrganization.class);
            when(organization.currentCompany()).thenReturn(company);
            when(organization.currentStore()).thenReturn(store);
            var rows = new ArrayList<Map<String, Object>>();
            for (int i = 1; i <= 55; i++) rows.add(Map.of("date", "14/11/2026", numberKey,
                    "ALM-%03d".formatted(i), "warehouse", "GENERAL", "productCount", new BigDecimal("3.75"),
                    "comment", "Observación completa de almacén 中文 — referencia " + i,
                    "total", new BigDecimal("12.34")));
            for (boolean empty : List.of(false, true)) {
                byte[] xlsx = TicketReportWorkbookLayout.write(request, empty ? List.of() : rows, organization);
                var excel = mock(SalesReportExcelExportService.class);
                when(excel.export(any(), any())).thenReturn(xlsx);
                byte[] pdf = new SalesReportPdfExportService(excel).export(request, mock(Authentication.class));
                var directory = Path.of("target", "warehouse-design");
                Files.createDirectories(directory);
                String stem = title + (empty ? "-vacio" : "-multipagina");
                Files.write(directory.resolve(stem + ".xlsx"), xlsx);
                Files.write(directory.resolve(stem + ".pdf"), pdf);
                try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx));
                     var document = Loader.loadPDF(pdf)) {
                    var sheet = workbook.getSheetAt(0);
                    assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
                    if (!empty) {
                        assertThat(sheet.getRow(9).getCell(3).getNumericCellValue()).isEqualTo(3.75);
                        assertThat(sheet.getRow(64).getCell(5).getNumericCellValue()).isEqualTo(678.70);
                    }
                    assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(empty ? 1 : 2);
                    String text = new PDFTextStripper().getText(document);
                    assertThat(text).contains(empty ? "Sin datos" : "ALM-055", "TOTAL");
                    if (!empty) assertThat(text).contains("3,75", "12,34 €", "678,70 €");
                    for (int page = 1; page <= document.getNumberOfPages(); page++) {
                        var stripper = new PDFTextStripper();
                        stripper.setStartPage(page);
                        stripper.setEndPage(page);
                        String pageText = stripper.getText(document);
                        assertThat(pageText).contains("Documento", "Comentario", "Página " + page + " de " + document.getNumberOfPages());
                        if (page == 1) assertThat(pageText).contains("Empresa Demo", "Tienda Demo");
                        else assertThat(pageText).doesNotContain("Empresa Demo", "Tienda Demo");
                    }
                }
            }
        }
    }

    @Test
    void purchaseReportsExportRealMultipageAndEmptyFixtures() throws Exception {
        for (String reportKey : List.of("salesReport.inputInvoices", "salesReport.inputDeliveryNotes")) {
            boolean invoice = "salesReport.inputInvoices".equals(reportKey);
            String title = invoice ? "Facturas de compra" : "Albaranes de compra";
            String numberKey = invoice ? "invoice" : "deliveryNote";
            var columns = List.of(
                    new SalesReportExportRequest.Column("date", "Fecha"),
                    new SalesReportExportRequest.Column(numberKey, invoice ? "Factura" : "Albarán"),
                    new SalesReportExportRequest.Column("supplier", "Proveedor"),
                    new SalesReportExportRequest.Column("supplierName", "Nombre proveedor"),
                    new SalesReportExportRequest.Column("warehouse", "Almacén"),
                    new SalesReportExportRequest.Column("productCount", "Cantidad"),
                    new SalesReportExportRequest.Column("status", "Estado"),
                    new SalesReportExportRequest.Column("comment", "Comentario"),
                    new SalesReportExportRequest.Column("subtotal", "Subtotal"),
                    new SalesReportExportRequest.Column("globalDiscount", "Descuento global"),
                    new SalesReportExportRequest.Column("total", "Total"));
            var request = new SalesReportExportRequest(reportKey, null, "", columns);
            var rows = new ArrayList<Map<String, Object>>();
            for (int i = 1; i <= 45; i++) {
                var row = new LinkedHashMap<String, Object>();
                row.put("date", "14/11/2026");
                row.put(numberKey, "COMPRA-%03d".formatted(i));
                row.put("supplier", "P-001");
                row.put("supplierName", "Proveedor de prueba");
                row.put("warehouse", "GENERAL");
                row.put("productCount", new BigDecimal("3.75"));
                row.put("status", "CONFIRMADA");
                row.put("comment", "Referencia externa larga — prueba de paginación 中文");
                row.put("subtotal", new BigDecimal("105.25"));
                row.put("globalDiscount", new BigDecimal("10.25"));
                row.put("total", new BigDecimal("99.99"));
                rows.add(row);
            }
            for (boolean empty : List.of(false, true)) {
                var company = mock(Company.class);
                when(company.getRazonSocial()).thenReturn("Empresa Demo");
                when(company.getTaxId()).thenReturn("B12345678");
                var store = mock(Store.class);
                when(store.getCodigoTienda()).thenReturn("001");
                when(store.getNombreEfectivo()).thenReturn("Tienda Demo");
                when(store.getTimezone()).thenReturn("Europe/Madrid");
                var organization = mock(CurrentOrganization.class);
                when(organization.currentCompany()).thenReturn(company);
                when(organization.currentStore()).thenReturn(store);
                byte[] xlsx = TicketReportWorkbookLayout.write(request, empty ? List.of() : rows, organization);
                var excel = mock(SalesReportExcelExportService.class);
                when(excel.export(any(), any())).thenReturn(xlsx);
                byte[] pdf = new SalesReportPdfExportService(excel).export(request, mock(Authentication.class));
                var directory = Path.of("target", "purchase-sales-design");
                Files.createDirectories(directory);
                String stem = title + (empty ? "-vacio" : "-multipagina");
                Files.write(directory.resolve(stem + ".xlsx"), xlsx);
                Files.write(directory.resolve(stem + ".pdf"), pdf);
                try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx));
                     var document = Loader.loadPDF(pdf)) {
                    var sheet = workbook.getSheet(title);
                    assertThat(sheet).isNotNull();
                    assertThat(sheet.getColumnWidth(3)).isEqualTo(36 * 256);
                    assertThat(sheet.getRow(0).getCell(0).getStringCellValue())
                            .isEqualTo("INFORME DE " + title.toUpperCase(java.util.Locale.ROOT));
                    assertThat(sheet.getLastRowNum()).isEqualTo(empty ? 9 : 54);
                    if (!empty) {
                        assertThat(sheet.getRow(9).getCell(5).getNumericCellValue()).isEqualTo(3.75);
                        assertThat(sheet.getRow(9).getCell(5).getCellStyle().getDataFormatString()).doesNotContain("€", "%");
                        assertThat(sheet.getRow(9).getCell(9).getNumericCellValue()).isEqualTo(0.1025);
                        assertThat(sheet.getRow(9).getCell(9).getCellStyle().getDataFormatString()).contains("%").doesNotContain("€");
                        assertThat(sheet.getRow(54).getCell(8).getNumericCellValue()).isEqualTo(4736.25);
                        assertThat(sheet.getRow(54).getCell(9).getStringCellValue()).isBlank();
                        assertThat(sheet.getRow(54).getCell(10).getNumericCellValue()).isEqualTo(4499.55);
                    }
                    assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(empty ? 1 : 2);
                    String text = new PDFTextStripper().getText(document);
                    assertThat(text).contains("INFORME DE " + title.toUpperCase(java.util.Locale.ROOT),
                            "Empresa Demo", "Tienda Demo", "Proveedor", "TOTAL");
                    assertThat(text).contains(empty ? "Sin datos" : "COMPRA-045");
                    if (!empty) assertThat(text).contains("10,25%", "3,75", "99,99 €");
                    for (int page = 1; page <= document.getNumberOfPages(); page++) {
                        var stripper = new PDFTextStripper();
                        stripper.setStartPage(page);
                        stripper.setEndPage(page);
                        String pageText = stripper.getText(document);
                        assertThat(pageText).contains("Página " + page + " de " + document.getNumberOfPages());
                        if (page == 1) assertThat(pageText).contains("Empresa Demo", "Tienda Demo");
                        else assertThat(pageText).doesNotContain("Empresa Demo", "Tienda Demo");
                    }
                }
            }
        }
    }

    @Test
    void commercialDocumentReportsExportRealMultipageAndEmptyFixtures() throws Exception {
        for (String reportKey : List.of("salesReport.invoices", "salesReport.deliveryNotes")) {
            boolean invoice = "salesReport.invoices".equals(reportKey);
            String title = invoice ? "Facturas" : "Albaranes";
            String numberKey = invoice ? "invoice" : "deliveryNote";
            var columns = new ArrayList<>(List.of(new SalesReportExportRequest.Column("date", "Fecha"),
                    new SalesReportExportRequest.Column("time", "Hora"),
                    new SalesReportExportRequest.Column(numberKey, title),
                    new SalesReportExportRequest.Column("customer", "Código cliente"),
                    new SalesReportExportRequest.Column("customerName", "Cliente"),
                    new SalesReportExportRequest.Column("status", "Estado"),
                    new SalesReportExportRequest.Column("comment", "Comentario")));
            if (invoice) columns.add(new SalesReportExportRequest.Column("pending", "Pendiente"));
            columns.add(new SalesReportExportRequest.Column("total", "Importe"));
            var request = new SalesReportExportRequest(reportKey, null, "", columns);
            var rows = new ArrayList<Map<String, Object>>();
            for (int i = 1; i <= 45; i++) {
                var row = new LinkedHashMap<String, Object>();
                row.put("date", "14/11/2026");
                row.put("time", "10:30");
                row.put(numberKey, "DOC-%03d".formatted(i));
                row.put("customer", "C-001");
                row.put("customerName", "Cliente de prueba");
                row.put("status", "CONFIRMADO");
                row.put("comment", "Descripción comercial extensa — revisión de filas y columnas \u4e2d\u6587");
                if (invoice) row.put("pending", new BigDecimal("2.10"));
                row.put("total", new BigDecimal("12.10"));
                rows.add(row);
            }
            for (boolean empty : List.of(false, true)) {
                var company = mock(Company.class);
                when(company.getRazonSocial()).thenReturn("Empresa Demo");
                when(company.getTaxId()).thenReturn("B12345678");
                var store = mock(Store.class);
                when(store.getCodigoTienda()).thenReturn("001");
                when(store.getNombreEfectivo()).thenReturn("Tienda Demo");
                when(store.getTimezone()).thenReturn("Europe/Madrid");
                var organization = mock(CurrentOrganization.class);
                when(organization.currentCompany()).thenReturn(company);
                when(organization.currentStore()).thenReturn(store);
                byte[] xlsx = TicketReportWorkbookLayout.write(request, empty ? List.of() : rows, organization);
                var excel = mock(SalesReportExcelExportService.class);
                when(excel.export(any(), any())).thenReturn(xlsx);
                byte[] pdf = new SalesReportPdfExportService(excel).export(request, mock(Authentication.class));
                var directory = Path.of("target", "commercial-sales-design");
                Files.createDirectories(directory);
                String stem = title + (empty ? "-vacio" : "-multipagina");
                Files.write(directory.resolve(stem + ".xlsx"), xlsx);
                Files.write(directory.resolve(stem + ".pdf"), pdf);

                try (var document = Loader.loadPDF(pdf)) {
                    assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(empty ? 1 : 2);
                    String allText = new PDFTextStripper().getText(document);
                    assertThat(allText).contains("INFORME DE " + title.toUpperCase(java.util.Locale.ROOT),
                            "Empresa Demo", "Tienda Demo", title, "Importe", "TOTAL");
                    assertThat(allText).contains(empty ? "Sin datos" : "DOC-045");
                    if (!empty) assertThat(allText).contains("CONFIRMADO", "C-001");
                    if (!empty) assertThat(allText).contains("544,50 €");
                    for (int page = 1; page <= document.getNumberOfPages(); page++) {
                        var stripper = new PDFTextStripper();
                        stripper.setStartPage(page);
                        stripper.setEndPage(page);
                        String pageText = stripper.getText(document);
                        assertThat(pageText).contains("Fecha", title, "Importe",
                                "Página " + page + " de " + document.getNumberOfPages());
                        if (page > 1) assertThat(pageText).doesNotContain("Empresa Demo", "Tienda Demo");
                    }
                }
            }
        }
    }

    @Test
    void ticketPdfConsumesRealTicketWorkbookAndItsEffectiveTotal() throws Exception {
        var columns = List.of(
                new SalesReportExportRequest.Column("date", "Fecha"),
                new SalesReportExportRequest.Column("time", "Hora"),
                new SalesReportExportRequest.Column("ticket", "Nº ticket"),
                new SalesReportExportRequest.Column("terminal", "Terminal"),
                new SalesReportExportRequest.Column("customer", "Cliente"),
                new SalesReportExportRequest.Column("payment", "Forma de pago"),
                new SalesReportExportRequest.Column("total", "Importe"));
        var request = new SalesReportExportRequest("salesReport.tickets",
                new SalesReportExportRequest.Filters("2026-11-01", "2026-11-14",
                        null, null, null, null, null, null, null), "", columns);
        var company = mock(Company.class);
        when(company.getRazonSocial()).thenReturn("Empresa Demo");
        when(company.getTaxId()).thenReturn("B12345678");
        var store = mock(Store.class);
        when(store.getCodigoTienda()).thenReturn("001");
        when(store.getNombreEfectivo()).thenReturn("Tienda Demo");
        when(store.getTimezone()).thenReturn("Europe/Madrid");
        when(store.getDireccion()).thenReturn(java.util.Map.of("linea1", "Calle Mayor 1"));
        var organization = mock(CurrentOrganization.class);
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        var row = new SalesReportExcelExportService.TicketExportRow(new BigDecimal("9.95"));
        row.put("date", "14/11/2026");
        row.put("time", "10:30");
        row.put("ticket", "T-REAL-001");
        row.put("terminal", "Caja 001");
        row.put("customer", "Cliente con acentos: José · 客户中文 ".repeat(20) + "FIN_CLIENTE");
        row.put("payment", "Tarjeta, efectivo");
        row.put("total", new BigDecimal("12.10"));
        byte[] xlsx = TicketReportWorkbookLayout.write(request, List.of(row), organization);
        Path directory = Path.of("target", "tickets-sales-design");
        Files.createDirectories(directory);
        Files.write(directory.resolve("Tickets-real.xlsx"), xlsx);
        var excel = mock(SalesReportExcelExportService.class);
        when(excel.export(any(), any())).thenReturn(xlsx);
        byte[] pdf = new SalesReportPdfExportService(excel).export(request, mock(Authentication.class));
        Files.write(directory.resolve("Tickets-real.pdf"), pdf);
        try (var document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(document.getPage(0).getMediaBox().getWidth())
                    .isEqualTo(org.apache.pdfbox.pdmodel.common.PDRectangle.A4.getWidth());
            assertThat(document.getPage(0).getMediaBox().getHeight())
                    .isEqualTo(org.apache.pdfbox.pdmodel.common.PDRectangle.A4.getHeight());
            assertThat(text).contains("Empresa Demo", "001 - Tienda Demo", "T-REAL-001", "9,95 €");
            assertThat(text.replaceAll("\\s+", " ")).contains("Tarjeta, efectivo");
            assertThat(text.replaceAll("\\s+", "")).contains("FIN_CLIENTE");
            assertThat(text).contains("José", "客户中文");
        }
    }

    @Test
    void realTicketWorkbookSupportsOnlyTotalWithNoRows() throws Exception {
        var columns = List.of(new SalesReportExportRequest.Column("total", "Importe"));
        var request = ticketRequest(columns);
        byte[] pdf = pdfFromRealWorkbook(request, List.of(), "Tickets-solo-total-vacio");
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(document))
                    .contains("Sin datos", "Importe", "TOTAL", "0,00 €", "Página 1 de 1");
        }
    }

    @Test
    void realTicketWorkbookKeepsAnOversizedRowAcrossPages() throws Exception {
        var columns = List.of(new SalesReportExportRequest.Column("date", "Fecha"),
                new SalesReportExportRequest.Column("comment", "Comentario"),
                new SalesReportExportRequest.Column("total", "Importe"));
        var request = ticketRequest(columns);
        var row = new SalesReportExcelExportService.TicketExportRow(new BigDecimal("15.00"));
        row.put("date", "14/11/2026");
        row.put("comment", "comentario extenso\ncon tabulación\t".repeat(350) + "FINAL_DE_FILA");
        row.put("total", new BigDecimal("15.00"));
        byte[] pdf = pdfFromRealWorkbook(request, List.of(row), "Tickets-fila-extensa");
        try (var document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            assertThat(document.getPage(0).getMediaBox().getWidth())
                    .isEqualTo(org.apache.pdfbox.pdmodel.common.PDRectangle.A4.getWidth());
            assertThat(text.replaceAll("\\s+", "")).contains("FINAL_DE_FILA");
            assertThat(text).contains("15,00 €");
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                var stripper = new PDFTextStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document);
                assertThat(pageText).contains("Fecha", "Comentario", "Importe",
                        "Página " + page + " de " + document.getNumberOfPages());
                if (page == 1) assertThat(pageText).contains("Empresa Demo", "INFORME DE TICKETS");
                else assertThat(pageText).doesNotContain("Empresa Demo", "INFORME DE TICKETS", "B12345678");
            }
            assertThat(positionOf(document, 2, "Fecha").getYDirAdj()).isLessThan(70f);
        }
    }

    @Test
    void ticketPdfUsesSeparateTableColumnsForShortFields() throws Exception {
        var columns = List.of(new SalesReportExportRequest.Column("date", "Fecha"),
                new SalesReportExportRequest.Column("time", "Hora"),
                new SalesReportExportRequest.Column("ticket", "Ticket"),
                new SalesReportExportRequest.Column("terminal", "Terminal"),
                new SalesReportExportRequest.Column("customer", "Cliente"),
                new SalesReportExportRequest.Column("payment", "Forma de pago"),
                new SalesReportExportRequest.Column("total", "Importe"));
        var row = new SalesReportExcelExportService.TicketExportRow(new BigDecimal("12.10"));
        row.put("date", "14/11/2026");
        row.put("time", "10:30");
        row.put("ticket", "T-001");
        row.put("terminal", "Caja 001");
        row.put("customer", "Cliente de prueba con nombre compuesto");
        row.put("payment", "Tarjeta bancaria");
        row.put("total", new BigDecimal("12.10"));
        var pdf = pdfFromRealWorkbook(ticketRequest(columns), List.of(row), "Tickets-campos-densos");
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(document.getPage(0).getMediaBox().getWidth())
                    .isEqualTo(org.apache.pdfbox.pdmodel.common.PDRectangle.A4.getWidth());
            assertThat(new PDFTextStripper().getText(document).replaceAll("\\s+", " "))
                    .contains("T-001", "Cliente de prueba con nombre compuesto", "Tarjeta bancaria");
            var date = positionOf(document, 1, "14/11/2026", true);
            var time = positionOf(document, 1, "10:30");
            var ticket = positionOf(document, 1, "T-001");
            assertThat(time.getXDirAdj()).isGreaterThan(date.getXDirAdj() + 35);
            assertThat(ticket.getXDirAdj()).isGreaterThan(time.getXDirAdj() + 25);
            assertThat(time.getYDirAdj()).isCloseTo(date.getYDirAdj(), org.assertj.core.data.Offset.offset(0.5f));
            assertThat(ticket.getYDirAdj()).isCloseTo(date.getYDirAdj(), org.assertj.core.data.Offset.offset(0.5f));
        }
    }

    @Test
    void manyShortTicketRowsStayPortrait() throws Exception {
        var columns = List.of(new SalesReportExportRequest.Column("date", "Fecha"),
                new SalesReportExportRequest.Column("ticket", "Ticket"),
                new SalesReportExportRequest.Column("total", "Importe"));
        byte[] pdf = ticketsPdf(columns, 40, false);
        save("Tickets-muchas-filas-cortas.pdf", pdf);
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                assertThat(document.getPage(i).getMediaBox().getWidth())
                        .isEqualTo(org.apache.pdfbox.pdmodel.common.PDRectangle.A4.getWidth());
            }
            assertThat(new PDFTextStripper().getText(document)).contains("T-001", "T-040");
        }
    }

    @Test
    void tablePreservesReorderedColumnsAndAlignsTheEffectiveTotal() throws Exception {
        var columns = List.of(new SalesReportExportRequest.Column("total", "Importe"),
                new SalesReportExportRequest.Column("base", "Base"),
                new SalesReportExportRequest.Column("ticket", "Ticket"));
        var row = new SalesReportExcelExportService.TicketExportRow(new BigDecimal("9.95"));
        row.put("total", new BigDecimal("12.10"));
        row.put("base", new BigDecimal("10.00"));
        row.put("ticket", "T-ORDERED");
        byte[] pdf = pdfFromRealWorkbook(ticketRequest(columns), List.of(row), "Tickets-tabla-orden");
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(positionOf(document, 1, "Importe").getXDirAdj())
                    .isLessThan(positionOf(document, 1, "Base").getXDirAdj());
            assertThat(positionOf(document, 1, "Base").getXDirAdj())
                    .isLessThan(positionOf(document, 1, "Ticket").getXDirAdj());
            var displayed = positionOf(document, 1, "12,10");
            var summary = positionOf(document, 1, "9,95");
            assertThat(summary.getFontSizeInPt()).isEqualTo(14f);
            assertThat(summary.getXDirAdj()).isLessThan(positionOf(document, 1, "Base").getXDirAdj());
            assertThat(displayed.getYDirAdj()).isLessThan(summary.getYDirAdj());
            assertThat(new PDFTextStripper().getText(document)).contains("TOTAL", "T-ORDERED", "10,00 €");
        }
    }

    @Test
    void tableKeepsEveryAvailableColumnInsideThePage() throws Exception {
        String[] keys = {"date", "time", "ticket", "status", "invoiced", "terminal", "user", "customer",
                "payment", "comment", "base", "tax", "discount", "memberBalance", "total"};
        String[] labels = {"Fecha", "Hora", "Ticket", "Estado", "Facturado", "Terminal", "Usuario", "Cliente",
                "Pago", "Comentario", "Base", "Impuesto", "Descuento", "Saldo", "Importe"};
        var columns = new java.util.ArrayList<SalesReportExportRequest.Column>();
        var row = new SalesReportExcelExportService.TicketExportRow(new BigDecimal("12.10"));
        String[] values = {"14/11/2026", "10:30", "T-ALL", "CONFIRMADO", "FV-001", "Caja 001", "Ana", "C-001",
                "Tarjeta", "Observación del ticket"};
        for (int i = 0; i < keys.length; i++) {
            columns.add(new SalesReportExportRequest.Column(keys[i], labels[i]));
            row.put(keys[i], i < values.length ? values[i] : new BigDecimal("12.10"));
        }
        byte[] pdf = pdfFromRealWorkbook(ticketRequest(columns), List.of(row), "Tickets-tabla-completa");
        try (var document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document).replaceAll("\\s+", "");
            for (String label : labels) assertThat(text).contains(label);
            for (String value : values) assertThat(text).contains(value.replaceAll("\\s+", ""));
            float width = document.getPage(0).getMediaBox().getWidth();
            assertThat(width).isEqualTo(org.apache.pdfbox.pdmodel.common.PDRectangle.A4.getHeight());
            var bounds = new PDFTextStripper() {
                @Override
                protected void processTextPosition(org.apache.pdfbox.text.TextPosition position) {
                    assertThat(position.getXDirAdj()).isGreaterThanOrEqualTo(19f);
                    assertThat(position.getXDirAdj() + position.getWidthDirAdj()).isLessThanOrEqualTo(width - 19f);
                    super.processTextPosition(position);
                }
            };
            bounds.getText(document);
        }
    }

    private SalesReportExportRequest ticketRequest(List<SalesReportExportRequest.Column> columns) {
        return new SalesReportExportRequest("salesReport.tickets",
                new SalesReportExportRequest.Filters(null, null, null, null, null, null, null, null, null),
                "", columns);
    }

    private byte[] pdfFromRealWorkbook(SalesReportExportRequest request,
            List<? extends java.util.Map<String, Object>> rows, String stem) throws Exception {
        var company = mock(Company.class);
        when(company.getRazonSocial()).thenReturn("Empresa Demo");
        when(company.getTaxId()).thenReturn("B12345678");
        var store = mock(Store.class);
        when(store.getCodigoTienda()).thenReturn("001");
        when(store.getNombreEfectivo()).thenReturn("Tienda Demo");
        when(store.getTimezone()).thenReturn("Europe/Madrid");
        var organization = mock(CurrentOrganization.class);
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        byte[] xlsx = TicketReportWorkbookLayout.write(request,
                new java.util.ArrayList<java.util.Map<String, Object>>(rows), organization);
        var excel = mock(SalesReportExcelExportService.class);
        when(excel.export(any(), any())).thenReturn(xlsx);
        byte[] pdf = new SalesReportPdfExportService(excel).export(request, mock(Authentication.class));
        Path directory = Path.of("target", "tickets-sales-design");
        Files.createDirectories(directory);
        Files.write(directory.resolve(stem + ".xlsx"), xlsx);
        Files.write(directory.resolve(stem + ".pdf"), pdf);
        return pdf;
    }

    @Test
    void ticketReportUsesA4MetadataDetailAndWorkbookSummary() throws Exception {
        var columns = List.of(
                new SalesReportExportRequest.Column("date", "Fecha"),
                new SalesReportExportRequest.Column("time", "Hora"),
                new SalesReportExportRequest.Column("ticket", "Ticket"),
                new SalesReportExportRequest.Column("comment", "Comentario"),
                new SalesReportExportRequest.Column("base", "Base"),
                new SalesReportExportRequest.Column("tax", "Impuesto"),
                new SalesReportExportRequest.Column("total", "Importe"));
        byte[] pdf = ticketsPdf(columns, 2, false);
        save("Tickets.pdf", pdf);
        try (var document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(document.getPage(0).getMediaBox().getWidth())
                    .isEqualTo(org.apache.pdfbox.pdmodel.common.PDRectangle.A4.getWidth());
            assertThat(document.getPage(0).getMediaBox().getHeight())
                    .isEqualTo(org.apache.pdfbox.pdmodel.common.PDRectangle.A4.getHeight());
            assertThat(text).contains("Empresa Demo", "Tienda Demo", "01/11/2026 - 14/11/2026",
                    "Fecha", "Ticket", "T-001", "Comentario", "Base", "Impuesto",
                    "10,00 €", "2,10 €", "12,10 €", "TOTAL", "Página 1 de 1");
            assertThat(text.replaceAll("\\s+", " ")).contains("Línea larga de prueba");
        }
    }

    @Test
    void ticketReportPaginatesLongRowsWithoutLosingSelectedFields() throws Exception {
        var columns = List.of(
                new SalesReportExportRequest.Column("date", "Fecha"),
                new SalesReportExportRequest.Column("time", "Hora"),
                new SalesReportExportRequest.Column("ticket", "Ticket"),
                new SalesReportExportRequest.Column("comment", "Comentario"),
                new SalesReportExportRequest.Column("total", "Importe"));
        byte[] pdf = ticketsPdf(columns, 8, true);
        save("Tickets-multipagina.pdf", pdf);
        try (var document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                assertThat(document.getPage(i).getMediaBox().getWidth())
                        .isEqualTo(org.apache.pdfbox.pdmodel.common.PDRectangle.A4.getWidth());
            }
            assertThat(text).contains("T-001", "T-008", "12,10 €");
            assertThat(text.replaceAll("\\s+", "")).contains("FINAL_DEL_COMENTARIO");
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                var stripper = new PDFTextStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document);
                assertThat(pageText).contains("Fecha", "Hora", "Ticket", "Comentario", "Importe",
                        "Página " + page + " de " + document.getNumberOfPages());
                if (page == 1) assertThat(pageText).contains("Empresa Demo");
                else assertThat(pageText).doesNotContain("Empresa Demo", "Tienda Demo", "B12345678");
                if (pageText.contains("TOTAL")) assertThat(pageText).contains("12,10 €");
            }
        }
    }

    @Test
    void ticketReportHandlesEmptyAndSingleColumnWithoutDateOrTotal() throws Exception {
        var columns = List.of(new SalesReportExportRequest.Column("ticket", "Nº ticket"));
        byte[] pdf = ticketsPdf(columns, 0, false);
        save("Tickets-vacio.pdf", pdf);
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(document)).contains("Sin datos", "Nº ticket", "Página 1 de 1");
        }
    }

    @Test
    void formatsCurrencyWithoutExposingTheExcelLocaleToken() throws Exception {
        var excel = mock(SalesReportExcelExportService.class);
        var authentication = mock(Authentication.class);
        when(excel.export(any(), any())).thenReturn(workbookWithCurrency());
        var service = new SalesReportPdfExportService(excel);
        var request = new SalesReportExportRequest(
                "salesReport.dailySales",
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

    private byte[] ticketsPdf(List<SalesReportExportRequest.Column> columns, int count, boolean longComment)
            throws Exception {
        var excel = mock(SalesReportExcelExportService.class);
        when(excel.export(any(), any())).thenReturn(ticketWorkbook(columns, count, longComment));
        var request = new SalesReportExportRequest("salesReport.tickets",
                new SalesReportExportRequest.Filters(null, null, null, null, null, null, null, null, null),
                "", columns);
        return new SalesReportPdfExportService(excel).export(request, mock(Authentication.class));
    }

    private byte[] ticketWorkbook(List<SalesReportExportRequest.Column> columns, int count,
            boolean longComment) throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Tickets");
            sheet.createRow(0).createCell(0).setCellValue("Informe de tickets");
            String[][] meta = {{"Empresa", "Empresa Demo"}, {"NIF", "B12345678"},
                    {"Tienda", "Tienda Demo"}, {"Dirección", "Calle Mayor 1"},
                    {"Periodo", "01/11/2026 - 14/11/2026"}, {"Emitido", "14/11/2026 10:00"}};
            for (int i = 0; i < meta.length; i++) {
                var row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(meta[i][0]);
                row.createCell(1).setCellValue(meta[i][1]);
            }
            sheet.createRow(7);
            var header = sheet.createRow(8);
            for (int i = 0; i < columns.size(); i++) header.createCell(i).setCellValue(columns.get(i).label());
            var currency = workbook.createCellStyle();
            currency.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00 [$€-es-ES]"));
            for (int rowNumber = 0; rowNumber < count; rowNumber++) {
                var row = sheet.createRow(9 + rowNumber);
                for (int i = 0; i < columns.size(); i++) {
                    var cell = row.createCell(i);
                    switch (columns.get(i).key()) {
                        case "date" -> cell.setCellValue("14/11/2026");
                        case "time" -> cell.setCellValue("10:30");
                        case "ticket" -> cell.setCellValue("T-%03d".formatted(rowNumber + 1));
                        case "comment" -> cell.setCellValue(longComment
                                ? "Línea larga de prueba ".repeat(20) + "FINAL_DEL_COMENTARIO"
                                : "Línea larga de prueba");
                        case "base" -> { cell.setCellValue(10); cell.setCellStyle(currency); }
                        case "tax" -> { cell.setCellValue(2.10); cell.setCellStyle(currency); }
                        case "total" -> { cell.setCellValue(12.10); cell.setCellStyle(currency); }
                        default -> cell.setCellValue("");
                    }
                }
            }
            if (count > 0) {
                var total = sheet.createRow(9 + count);
                total.createCell(0).setCellValue("TOTAL");
                for (int i = 1; i < columns.size(); i++) {
                    if (List.of("base", "tax", "total").contains(columns.get(i).key())) {
                        var cell = total.createCell(i);
                        cell.setCellValue("total".equals(columns.get(i).key()) ? 12.10 :
                                "base".equals(columns.get(i).key()) ? 10 : 2.10);
                        cell.setCellStyle(currency);
                    }
                }
            }
            sheet.createFreezePane(0, 9);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private void save(String name, byte[] pdf) throws Exception {
        Path path = Path.of("target", "tickets-sales-design", name);
        Files.createDirectories(path.getParent());
        Files.write(path, pdf);
    }

    private org.apache.pdfbox.text.TextPosition positionOf(
            org.apache.pdfbox.pdmodel.PDDocument document, int page, String value) throws Exception {
        return positionOf(document, page, value, false);
    }

    private org.apache.pdfbox.text.TextPosition positionOf(
            org.apache.pdfbox.pdmodel.PDDocument document, int page, String value, boolean last) throws Exception {
        var characters = new StringBuilder();
        var positions = new java.util.ArrayList<org.apache.pdfbox.text.TextPosition>();
        var stripper = new PDFTextStripper() {
            @Override
            protected void processTextPosition(org.apache.pdfbox.text.TextPosition position) {
                characters.append(position.getUnicode());
                for (int i = 0; i < position.getUnicode().length(); i++) positions.add(position);
                super.processTextPosition(position);
            }
        };
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        stripper.getText(document);
        int offset = last ? characters.lastIndexOf(value) : characters.indexOf(value);
        assertThat(offset).as("PDF contains %s on page %s", value, page).isNotNegative();
        return positions.get(offset);
    }
}
