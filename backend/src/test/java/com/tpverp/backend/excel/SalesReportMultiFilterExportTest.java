package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.CustomerDocumentReportFilter;
import com.tpverp.backend.document.DocumentAttributionResolver;
import com.tpverp.backend.document.DocumentReportService;
import com.tpverp.backend.document.DocumentReportView;
import com.tpverp.backend.document.DocumentService;
import com.tpverp.backend.document.DocumentStatus;
import com.tpverp.backend.document.DocumentView;
import com.tpverp.backend.document.RefundTenderType;
import com.tpverp.backend.document.TicketReportLifecycleStatus;
import com.tpverp.backend.document.TicketReportService;
import com.tpverp.backend.document.TicketReportView;
import com.tpverp.backend.document.WarehouseInputReportService;
import com.tpverp.backend.document.WarehouseInputReportView;
import com.tpverp.backend.inventory.WarehouseInputDocumentType;
import com.tpverp.backend.inventory.WarehouseInputStatus;
import com.tpverp.backend.inventory.WarehouseInputView;
import com.tpverp.backend.inventory.WarehouseOutputService;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.shared.api.PagedResult;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class SalesReportMultiFilterExportTest {
    private final DocumentReportService reports = mock(DocumentReportService.class);
    private final WarehouseInputReportService inputs = mock(WarehouseInputReportService.class);
    private final TicketReportService tickets = mock(TicketReportService.class);
    private final Authentication authentication = new UsernamePasswordAuthenticationToken("admin", "unused",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    private final SalesReportExcelExportService excel = new SalesReportExcelExportService(
            mock(DocumentService.class), reports, inputs, mock(WarehouseOutputService.class),
            mock(WarehouseRepository.class), organization(), mock(DocumentAttributionResolver.class),
            mock(AuditService.class), tickets);

    @Test
    void combinesEachSelectionWithOrAndDifferentFiltersWithAndWithoutDuplicatingMixedPayments() throws Exception {
        var mixed = invoice("MIXTO", "C-001", "Ana", "CAJA 1", "GENERAL", DocumentStatus.PAGADO, "EFECTIVO", "TARJETA");
        var card = invoice("TARJETA", "C-002", "Luis", "CAJA 2", "RESERVA", DocumentStatus.PARCIAL, "CARD");
        var transfer = invoice("TRANSFERENCIA", "C-001", "Ana", "CAJA 1", "GENERAL", DocumentStatus.PAGADO, "TRANSFERENCIA");
        var wrongCustomer = invoice("OTROCLIENTE", "C-0010", "Ana", "CAJA 1", "GENERAL", DocumentStatus.PAGADO, "EFECTIVO");
        var wrongUser = invoice("OTROUSUARIO", "C-001", "Otro", "CAJA 1", "GENERAL", DocumentStatus.PAGADO, "EFECTIVO");
        var wrongTerminal = invoice("OTRACAJA", "C-001", "Ana", "CAJA 3", "GENERAL", DocumentStatus.PAGADO, "EFECTIVO");
        var wrongWarehouse = invoice("OTROALMACEN", "C-001", "Ana", "CAJA 1", "OTRO", DocumentStatus.PAGADO, "EFECTIVO");
        var wrongStatus = invoice("OTROESTADO", "C-001", "Ana", "CAJA 1", "GENERAL", DocumentStatus.ANULADO, "EFECTIVO");
        when(reports.allInvoices(true, false)).thenReturn(List.of(mixed, card, transfer, wrongCustomer,
                wrongUser, wrongTerminal, wrongWarehouse, wrongStatus));
        var filters = new SalesReportExportRequest.Filters("", "", "legacy ignored", "legacy ignored", "", "legacy ignored", "", "", "",
                List.of("Ana", "Luis"), List.of("C-001", "C-002"), null, List.of("EFECTIVO", "TARJETA"),
                List.of("CAJA 1", "CAJA 2"), List.of("salesReport.status.paid", "salesReport.status.partial"), List.of("GENERAL", "RESERVA"));
        var request = request("salesReport.invoices", "invoice", filters);

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(excel.export(request, authentication)))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("MIXTO");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("TARJETA");
            assertThat(sheet.getRow(1).getCell(1).getNumericCellValue()).isEqualTo(12.10);
        }
        try (var pdf = Loader.loadPDF(new SalesReportPdfExportService(excel).export(request, authentication))) {
            assertThat(new PDFTextStripper().getText(pdf)).contains("MIXTO", "TARJETA")
                    .doesNotContain("TRANSFERENCIA", "OTROCLIENTE", "OTROUSUARIO", "OTRACAJA", "OTROALMACEN", "OTROESTADO");
        }
    }

    @Test
    void keepsLegacyCustomerSubstringAndEmptyListFallbackCompatible() throws Exception {
        var invoices = List.of(
                invoice("LEGACY", "C-001", "Ana", "CAJA 1", "GENERAL", DocumentStatus.PAGADO, "EFECTIVO"),
                invoice("OTHER", "C-002", "Luis", "CAJA 2", "GENERAL", DocumentStatus.PAGADO, "TARJETA"));
        when(reports.allInvoices(true, false)).thenReturn(invoices);
        var filters = new SalesReportExportRequest.Filters("", "", "Ana", "cliente c-001", "", "CASH", "", "", "",
                List.of(), List.of(), null, List.of(), null, null, null);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(excel.export(request("salesReport.invoices", "invoice", filters), authentication)))) {
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(1);
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("LEGACY");
        }
    }

    @Test
    void filtersExactSupplierCodesAndMultipleWarehousesAcrossPurchasePages() throws Exception {
        var firstPage = new PagedResult<>(List.of(input("INCLUIDA1", "P-001", "GENERAL"), input("EXCLUIDA", "P-0010", "GENERAL")), "next", true);
        var secondPage = new PagedResult<>(List.of(input("INCLUIDA2", "P-002", "RESERVA")), null, false);
        when(inputs.listPage(eq(WarehouseInputDocumentType.FACTURA_ENTRADA), eq(200), isNull(), any(), any(), eq(authentication)))
                .thenReturn(firstPage);
        when(inputs.listPage(eq(WarehouseInputDocumentType.FACTURA_ENTRADA), eq(200), eq("next"), any(), any(), eq(authentication)))
                .thenReturn(secondPage);
        var filters = new SalesReportExportRequest.Filters("", "", "", "", "", "", "", "", "",
                null, null, List.of("P-001", "P-002"), null, null, List.of("salesReport.status.confirmed"), List.of("GENERAL", "RESERVA"));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(excel.export(request("salesReport.inputInvoices", "invoice", filters), authentication)))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(10);
            assertThat(sheet.getRow(9).getCell(0).getStringCellValue()).isEqualTo("INCLUIDA1");
            assertThat(sheet.getRow(10).getCell(0).getStringCellValue()).isEqualTo("INCLUIDA2");
        }
    }

    @Test
    void exportsTicketLifecycleAndRefundPaymentMetadataFromTheSamePagedReportAsTheScreen() throws Exception {
        var refund = ticket("DEVOLUCION", "C-001", TicketReportLifecycleStatus.RETURNED, new BigDecimal("-12.10"), List.of("EFECTIVO"));
        when(refund.refundMethods()).thenReturn(List.of(RefundTenderType.CARD));
        var invoiced = ticket("FACTURADO", "C-002", TicketReportLifecycleStatus.INVOICED, new BigDecimal("12.10"), List.of("EFECTIVO", "CARD"));
        var other = ticket("CONFIRMADO", "C-002", TicketReportLifecycleStatus.CONFIRMED, new BigDecimal("12.10"), List.of("CARD"));
        when(tickets.list(eq(500), isNull(), isNull(), any()))
                .thenReturn(new PagedResult<>(List.of(refund), "next", true));
        when(tickets.list(eq(500), eq("next"), isNull(), any()))
                .thenReturn(new PagedResult<>(List.of(invoiced, other), null, false));
        var filters = new SalesReportExportRequest.Filters("2026-09-01", "2026-09-30", "", "", "", "", "", "", "",
                List.of("Ana"), List.of("C-001", "C-002"), null, List.of("TARJETA"), null,
                List.of("salesReport.status.returned", "salesReport.status.invoiced"), null);
        var request = request("salesReport.tickets", "ticket", filters);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(excel.export(request, authentication)))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("DEVOLUCION");
            assertThat(sheet.getRow(1).getCell(1).getNumericCellValue()).isEqualTo(-12.10);
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("FACTURADO");
        }
        verify(tickets).list(500, null, null, new CustomerDocumentReportFilter(null, null,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null, null));
    }

    @Test
    void selectsUnavailableAttributionOnlyInTheNewUserAndTerminalLists() throws Exception {
        var missing = invoice("SINATRIBUCION", "C-001", null, "  ", "GENERAL", DocumentStatus.PAGADO, "EFECTIVO");
        var known = invoice("CONATRIBUCION", "C-001", "Ana", "CAJA 1", "GENERAL", DocumentStatus.PAGADO, "EFECTIVO");
        when(reports.allInvoices(true, false)).thenReturn(List.of(missing, known));
        var filters = new SalesReportExportRequest.Filters("", "", "", "", "", "", "", "", "",
                List.of("salesReport.value.unavailable"), null, null, null, List.of("salesReport.value.unavailable"), null, null);
        assertExportNumbers(request("salesReport.invoices", "invoice", filters), "SINATRIBUCION");
        var legacy = new SalesReportExportRequest.Filters("", "", "salesReport.value.unavailable", "", "", "", "", "", "");
        assertExportNumbers(request("salesReport.invoices", "invoice", legacy));
    }

    @Test
    void customInvoicePaymentNamesRemainLiteralWhileKnownAliasesStillMatch() throws Exception {
        var exact = invoice("EXACTO", "C-001", "Ana", "CAJA 1", "GENERAL", DocumentStatus.PAGADO, "Mi Vale");
        var lower = invoice("MINUSCULAS", "C-001", "Ana", "CAJA 1", "GENERAL", DocumentStatus.PAGADO, "mi vale");
        var accent = invoice("ACENTO", "C-001", "Ana", "CAJA 1", "GENERAL", DocumentStatus.PAGADO, "Mí Vale");
        var discount = invoice("DESCUENTO", "C-001", "Ana", "CAJA 1", "GENERAL", DocumentStatus.PAGADO, "DISCOUNT");
        when(reports.allInvoices(true, false)).thenReturn(List.of(exact, lower, accent, discount));
        var filters = new SalesReportExportRequest.Filters("", "", "", "", "", "", "", "", "",
                null, null, null, List.of(" Mi Vale ", "salesReport.column.discount"), null, null, null);
        assertExportNumbers(request("salesReport.invoices", "invoice", filters), "EXACTO", "DESCUENTO");
    }

    @Test
    void aCustomPaymentContainingACommaDoesNotMatchTheDisplayTextOfMixedPayments() throws Exception {
        var custom = invoice("PERSONALIZADO", "C-001", "Ana", "CAJA 1", "GENERAL", DocumentStatus.PAGADO, "EFECTIVO, TARJETA");
        var mixed = invoice("MIXTO", "C-001", "Ana", "CAJA 1", "GENERAL", DocumentStatus.PAGADO, "EFECTIVO", "TARJETA");
        when(reports.allInvoices(true, false)).thenReturn(List.of(custom, mixed));
        var filters = new SalesReportExportRequest.Filters("", "", "", "", "", "", "", "", "",
                null, null, null, List.of("EFECTIVO, TARJETA"), null, null, null);
        assertExportNumbers(request("salesReport.invoices", "invoice", filters), "PERSONALIZADO");
        var legacy = new SalesReportExportRequest.Filters("", "", "", "", "", "EFECTIVO, TARJETA", "", "", "");
        assertExportNumbers(request("salesReport.invoices", "invoice", legacy), "PERSONALIZADO", "MIXTO");
    }

    @Test
    void multipleStatusesMatchDocumentStatusInsteadOfAnyPaymentWithTheSameName() throws Exception {
        var paid = invoice("PAGADO", "C-001", "Ana", "CAJA 1", "GENERAL", DocumentStatus.PAGADO, "PENDIENTE");
        var pending = invoice("PENDIENTE", "C-001", "Ana", "CAJA 1", "GENERAL", DocumentStatus.PENDIENTE, "EFECTIVO");
        when(reports.allInvoices(true, false)).thenReturn(List.of(paid, pending));
        var filters = new SalesReportExportRequest.Filters("", "", "", "", "", "", "", "", "",
                null, null, null, null, null, List.of("PENDIENTE"), null);
        assertExportNumbers(request("salesReport.invoices", "invoice", filters), "PENDIENTE");
        var legacy = new SalesReportExportRequest.Filters("", "", "", "", "", "", "", "PENDIENTE", "");
        assertExportNumbers(request("salesReport.invoices", "invoice", legacy), "PAGADO", "PENDIENTE");
    }

    @Test
    void ticketFiltersUseOnlyRecognizedDisplayedMethodsAndExcludeInternalCompensations() throws Exception {
        var cash = ticket("EFECTIVO", "C-001", TicketReportLifecycleStatus.CONFIRMED, new BigDecimal("12.10"),
                List.of("CASH", "COMPENSACION_DEVOLUCION", "PERSONALIZADO"));
        var hidden = ticket("INTERNO", "C-002", TicketReportLifecycleStatus.CONFIRMED, new BigDecimal("12.10"),
                List.of("COMPENSACION_DEVOLUCION", "PERSONALIZADO"));
        when(tickets.list(any(), any(), any(), any())).thenReturn(new PagedResult<>(List.of(cash, hidden), null, false));
        var cashFilters = new SalesReportExportRequest.Filters("", "", "", "", "", "", "", "", "",
                null, null, null, List.of("EFECTIVO"), null, null, null);
        assertExportNumbers(request("salesReport.tickets", "ticket", cashFilters), "EFECTIVO");
        var internalFilters = new SalesReportExportRequest.Filters("", "", "", "", "", "", "", "", "",
                null, null, null, List.of("COMPENSACION_DEVOLUCION", "PERSONALIZADO"), null, null, null);
        assertExportNumbers(request("salesReport.tickets", "ticket", internalFilters));
    }

    private void assertExportNumbers(SalesReportExportRequest request, String... expected) throws Exception {
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(excel.export(request, authentication)))) {
            var sheet = workbook.getSheetAt(0);
            var actual = new java.util.ArrayList<String>();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                actual.add(sheet.getRow(index).getCell(0).getStringCellValue());
            }
            assertThat(actual).containsExactly(expected);
        }
    }

    private SalesReportExportRequest request(String report, String numberColumn, SalesReportExportRequest.Filters filters) {
        return new SalesReportExportRequest(report, filters, "", List.of(
                new SalesReportExportRequest.Column(numberColumn, "Documento"),
                new SalesReportExportRequest.Column("total", "Total")));
    }

    private DocumentReportView invoice(String number, String customer, String user, String terminal, String warehouse,
            DocumentStatus status, String... methods) {
        var value = mock(DocumentReportView.class);
        when(value.numero()).thenReturn(number);
        when(value.tipo()).thenReturn(CommercialDocumentType.FACTURA_VENTA);
        when(value.estado()).thenReturn(status);
        when(value.fecha()).thenReturn(LocalDate.of(2026, 9, 22));
        when(value.total()).thenReturn(new BigDecimal("12.10"));
        when(value.clienteCodigo()).thenReturn(customer);
        when(value.clienteNombre()).thenReturn("CLIENTE " + customer);
        when(value.usuarioNombre()).thenReturn(user);
        when(value.terminalOrigenNombre()).thenReturn(terminal);
        when(value.almacenNombre()).thenReturn(warehouse);
        var payments = java.util.Arrays.stream(methods).map(method -> {
            var payment = mock(DocumentView.PaymentView.class);
            when(payment.methodName()).thenReturn(method);
            return payment;
        }).toList();
        when(value.payments()).thenReturn(payments);
        return value;
    }

    private WarehouseInputReportView input(String number, String supplier, String warehouse) {
        var value = mock(WarehouseInputView.class);
        when(value.documentType()).thenReturn(WarehouseInputDocumentType.FACTURA_ENTRADA);
        when(value.number()).thenReturn(number);
        when(value.date()).thenReturn(LocalDate.of(2026, 9, 22));
        when(value.lines()).thenReturn(List.of());
        when(value.status()).thenReturn(WarehouseInputStatus.CONFIRMADA);
        when(value.total()).thenReturn(new BigDecimal("12.10"));
        return new WarehouseInputReportView(value, supplier, "PROVEEDOR " + supplier, warehouse);
    }

    private TicketReportView ticket(String number, String customer, TicketReportLifecycleStatus status, BigDecimal total, List<String> methods) {
        var value = mock(TicketReportView.class);
        when(value.numero()).thenReturn(number);
        when(value.customerCode()).thenReturn(customer);
        when(value.estado()).thenReturn(DocumentStatus.CONFIRMADO);
        when(value.lifecycleStatus()).thenReturn(status);
        when(value.fecha()).thenReturn(LocalDate.of(2026, 9, 22));
        when(value.usuarioNombre()).thenReturn("Ana");
        when(value.total()).thenReturn(total);
        when(value.paymentMethods()).thenReturn(methods);
        return value;
    }

    private CurrentOrganization organization() {
        var organization = mock(CurrentOrganization.class);
        var company = mock(Company.class);
        var store = mock(Store.class);
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        when(company.getRazonSocial()).thenReturn("EMPRESA DEMO");
        when(company.getTaxId()).thenReturn("DEMO");
        when(store.getCodigoTienda()).thenReturn("01");
        when(store.getNombreEfectivo()).thenReturn("TIENDA DEMO");
        when(store.getMoneda()).thenReturn("EUR");
        return organization;
    }
}
