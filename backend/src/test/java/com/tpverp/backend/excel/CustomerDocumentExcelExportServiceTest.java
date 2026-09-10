package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.CustomerDocumentReportFilter;
import com.tpverp.backend.document.CustomerDocumentReportQueryRepository;
import com.tpverp.backend.document.DocumentStatus;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.util.DefaultTempFileCreationStrategy;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class CustomerDocumentExcelExportServiceTest {

    private final CurrentOrganization organization = mock(CurrentOrganization.class);
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final Customer customer = mock(Customer.class);
    private final CustomerDocumentReportQueryRepository queries = mock(CustomerDocumentReportQueryRepository.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private final CustomerDocumentExcelExportService service =
            new CustomerDocumentExcelExportService(organization, customers, queries, jdbc, audit);
    private final UUID storeId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();

    @BeforeEach
    void organization() {
        var store = mock(Store.class);
        var company = mock(Company.class);
        when(store.getId()).thenReturn(storeId);
        when(company.getId()).thenReturn(companyId);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(customers.findByIdAndCompanyId(customerId, companyId)).thenReturn(Optional.of(customer));
        when(customer.getClientId()).thenReturn("000042");
        when(customer.getDocumentNumber()).thenReturn("00123456A");
        when(customer.getFiscalName()).thenReturn("Cliente de la ficha BD");
    }

    @Test
    void loadedSelectionPreservesColumnAndIdOrderWithNumericDatesAmountsAndSafeText() throws Exception {
        var first = row("=HYPERLINK(\"example\")", "-12.35");
        var second = row("+SUM(1,2)", "30.10");
        when(customer.getFiscalName()).thenReturn("=HYPERLINK(\"cliente\")");
        stubRows(List.of(second, first));
        var columns = List.of(column("total", "金额"), column("number", "Number"),
                column("date", "Fecha"), column("status", "Estado"), column("type", "类型"),
                column("terminal", "Terminal"), column("user", "Usuario"));
        var request = request("tickets", null, "total", "asc", List.of(first.id(), second.id()), columns);

        var bytes = service.export(request, authentication("VENTA"));

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("客户单据");
            assertThat(sheet.getLastRowNum()).isEqualTo(7);
            assertText(sheet.getRow(0).getCell(1), "000042");
            assertText(sheet.getRow(1).getCell(1), "00123456A");
            assertText(sheet.getRow(2).getCell(1), "=HYPERLINK(\"cliente\")");
            assertThat(sheet.getMergedRegions()).extracting(region -> region.formatAsString())
                    .contains("B1:G1", "B2:G2", "B3:G3");
            assertText(sheet.getRow(3).getCell(0), "Filtros: Sin filtros");
            assertText(sheet.getRow(4).getCell(0), "金额");
            assertText(sheet.getRow(4).getCell(1), "Number");
            assertThat(sheet.getRow(4).getCell(0).getCellStyle().getFillForegroundColor())
                    .isEqualTo(IndexedColors.GREY_25_PERCENT.getIndex());
            assertThat(sheet.getRow(4).getCell(0).getCellStyle().getFillPattern())
                    .isEqualTo(FillPatternType.SOLID_FOREGROUND);
            assertThat(sheet.getRow(4).getCell(0).getCellStyle().getFont().getColor())
                    .isEqualTo(IndexedColors.BLACK.getIndex());
            assertThat(sheet.getRow(5).getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(5).getCell(0).getNumericCellValue()).isEqualTo(-12.35);
            assertThat(sheet.getRow(5).getCell(0).getCellStyle().getAlignment()).isEqualTo(HorizontalAlignment.RIGHT);
            assertText(sheet.getRow(5).getCell(1), first.number());
            assertText(sheet.getRow(6).getCell(1), second.number());
            assertThat(DateUtil.isCellDateFormatted(sheet.getRow(5).getCell(2))).isTrue();
            assertThat(sheet.getRow(5).getCell(2).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(first.date());
            assertText(sheet.getRow(5).getCell(3), "Confirmado");
            assertText(sheet.getRow(5).getCell(4), "小票");
            assertText(sheet.getRow(5).getCell(5), "Caja 1");
            assertText(sheet.getRow(5).getCell(6), "Vendedor");
            assertFormulaTotal(sheet.getRow(7).getCell(0), "SUM(A6:A7)", "17.75");
            assertThat(sheet.getRow(7).getCell(1).getStringCellValue()).isNotBlank();
            assertThat(sheet.getRow(7).getCell(0).getCellStyle().getDataFormatString())
                    .isEqualTo(sheet.getRow(5).getCell(0).getCellStyle().getDataFormatString());
            assertTotalStyle(sheet.getRow(7).getCell(0));
            assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 5);
            assertThat(sheet.getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A5:G7");
        }
        var parameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), parameters.capture(), any(RowMapper.class));
        assertThat(sql.getValue()).contains("document.tienda_id = :storeId", "document.cliente_id = :customerId",
                "document.tipo in (:types)", "document.id in (:ids)");
        assertThat(parameters.getValue().getValue("storeId")).isEqualTo(storeId);
        assertThat(parameters.getValue().getValue("customerId")).isEqualTo(customerId);
        assertThat(parameters.getValue().getValue("types")).isEqualTo(List.of("TICKET"));
        assertThat(parameters.getValue().getValue("ids")).isEqualTo(request.documentIds());
        verifyNoInteractions(queries);
        verify(audit).record(eq("CUSTOMER_DOCUMENTS_EXPORTED"), eq(AuditResult.EXITO),
                eq(Map.of("reportKey", "tickets", "customerId", customerId.toString(), "rows", 2, "filtered", false)));
    }

    @Test
    void filteredExportReadsAllMatchingPagesBeyondTheUiPageAndKeepsServerOrder() throws Exception {
        var firstPage = IntStream.range(0, 500).mapToObj(index -> row("T-" + index, "0.10")).toList();
        var lastPage = IntStream.range(500, 563)
                .mapToObj(index -> row("T-" + index, index == 562 ? "-12.10" : "0.20")).toList();
        var filters = new CustomerDocumentExportRequest.Filters(" T- ", DocumentStatus.CONFIRMADO,
                LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"));
        var request = request("tickets", filters, "number", "asc", null,
                List.of(column("number", "Número"), column("total", "Total")));
        var queryFilter = new CustomerDocumentReportFilter("T-", DocumentStatus.CONFIRMADO,
                filters.dateFrom(), filters.dateTo(), "number", "asc");
        when(queries.findPage(storeId, customerId, EnumSet.of(CommercialDocumentType.TICKET), queryFilter, null, 500))
                .thenReturn(new CustomerDocumentReportQueryRepository.Page(ids(firstPage), "next-page", true));
        when(queries.findPage(storeId, customerId, EnumSet.of(CommercialDocumentType.TICKET), queryFilter, "next-page", 500))
                .thenReturn(new CustomerDocumentReportQueryRepository.Page(ids(lastPage), null, false));
        when(jdbc.query(anyString(), any(SqlParameterSource.class), org.mockito.ArgumentMatchers
                .<RowMapper<CustomerDocumentExcelExportService.ExportRow>>any())).thenReturn(firstPage, lastPage);

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(request, authentication("TICKETS_READ"))))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(568);
            assertText(sheet.getRow(5).getCell(0), "T-0");
            assertText(sheet.getRow(505).getCell(0), "T-500");
            assertText(sheet.getRow(567).getCell(0), "T-562");
            assertFormulaTotal(sheet.getRow(568).getCell(1), "SUM(B6:B568)", "50.30");
            assertThat(sheet.getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A5:B568");
            assertText(sheet.getRow(3).getCell(0),
                    "Filtros: Búsqueda: T-; Estado: Confirmado; Desde: 01/09/2026; Hasta: 30/09/2026");
        }
        verify(queries).findPage(storeId, customerId, EnumSet.of(CommercialDocumentType.TICKET), queryFilter, "next-page", 500);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void rejectsIdsNotReturnedByTheScopedProjectionInsteadOfExportingAPartialSelection(CapturedOutput output)
            throws IOException {
        var previousTemporarySheets = temporarySheets();
        var present = row("T-1", "1.00");
        stubRows(List.of(present));
        var request = request("tickets", null, null, null,
                List.of(present.id(), UUID.randomUUID()), List.of(column("number", "Número")));
        assertThatThrownBy(() -> service.export(request, authentication("VENTA")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no están disponibles");
        verifyNoInteractions(audit, queries);
        assertThat(output.getAll()).doesNotContain("Failed to dispose sheet", "Stream closed");
        assertThat(temporarySheets()).isSubsetOf(previousTemporarySheets);
    }

    @Test
    void rejectsCustomerOutsideCurrentCompanyBeforeAnyDocumentRead() {
        when(customers.findByIdAndCompanyId(customerId, companyId)).thenReturn(Optional.empty());
        var request = request("tickets", null, null, null, List.of(UUID.randomUUID()), List.of(column("number", "Número")));
        assertThatThrownBy(() -> service.export(request, authentication("VENTA")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Cliente no encontrado");
        verifyNoInteractions(jdbc, queries, audit);
    }

    @Test
    void serviceAlsoEnforcesTheSpecificReportPermission() {
        var request = request("invoices", null, null, null, List.of(), List.of(column("number", "Número")));
        assertThatThrownBy(() -> service.export(request, authentication("TICKETS_READ")))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(jdbc, queries, audit, customers);
    }

    @Test
    void rejectsLoadedSelectionOverTheLimitBeforeQuerying() {
        var ids = IntStream.range(0, 50_001).mapToObj(index -> new UUID(0, index)).toList();
        var request = request("tickets", null, null, null, ids, List.of(column("number", "Número")));
        assertThatThrownBy(() -> service.export(request, authentication("VENTA")))
                .isInstanceOf(CustomerDocumentExcelExportService.ExportLimitExceededException.class);
        verifyNoInteractions(jdbc, queries, audit, customers);
    }

    @Test
    void rejectsFilteredResultsAboveFiftyThousandWithoutReturningATruncatedWorkbook() {
        var rows = IntStream.range(0, 500).mapToObj(index -> row("T-" + index, "1.00")).toList();
        var pageCount = new AtomicInteger();
        when(queries.findPage(eq(storeId), eq(customerId), any(), any(), any(), eq(500)))
                .thenAnswer(invocation -> new CustomerDocumentReportQueryRepository.Page(
                        ids(rows), "page-" + pageCount.incrementAndGet(), true));
        stubRows(rows);
        var request = request("tickets", new CustomerDocumentExportRequest.Filters("T-", null, null, null),
                null, null, null, List.of(column("number", "Número")));
        assertThatThrownBy(() -> service.export(request, authentication("VENTA")))
                .isInstanceOf(CustomerDocumentExcelExportService.ExportLimitExceededException.class);
        assertThat(pageCount.get()).isEqualTo(100);
        verify(jdbc, times(99)).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
        verifyNoInteractions(audit);
    }

    @Test
    void rejectsDuplicateColumnsDuplicateIdsAndAmbiguousFilteredSelection() {
        var id = UUID.randomUUID();
        var columns = List.of(column("number", "Número"));
        for (var request : List.of(
                request("tickets", null, null, null, List.of(id, id), columns),
                request("tickets", null, null, null, List.of(id), List.of(columns.getFirst(), columns.getFirst())),
                request("tickets", null, null, null, List.of(id), List.of(column("secret", "Secret"))),
                request("tickets", new CustomerDocumentExportRequest.Filters("T", null, null, null),
                        null, null, List.of(id), columns))) {
            assertThatThrownBy(() -> service.export(request, authentication("VENTA")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(jdbc, queries, audit, customers);
    }

    @Test
    void emptyLoadedSelectionIncludesCustomerVisibleHeadersAndNumericZero() throws Exception {
        var request = request("tickets", new CustomerDocumentExportRequest.Filters("  ", null, null, null),
                "date", "desc", List.of(), List.of(column("number", "Número")));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(request, authentication("VENTA"))))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(5);
            assertText(sheet.getRow(0).getCell(0), "Código del cliente");
            assertText(sheet.getRow(0).getCell(1), "000042");
            assertText(sheet.getRow(1).getCell(0), "NIF");
            assertText(sheet.getRow(1).getCell(1), "00123456A");
            assertText(sheet.getRow(2).getCell(0), "Nombre del cliente");
            assertText(sheet.getRow(2).getCell(1), "Cliente de la ficha BD");
            assertText(sheet.getRow(3).getCell(0), "Filtros: Sin filtros");
            assertText(sheet.getRow(4).getCell(0), "Número");
            assertThat(sheet.getRow(4).getLastCellNum()).isEqualTo((short) 1);
            assertText(sheet.getRow(5).getCell(0), "Total documentos");
            assertThat(sheet.getRow(5).getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(5).getCell(1).getNumericCellValue()).isZero();
            assertThat(sheet.getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A5");
            assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 5);
        }
        verifyNoInteractions(jdbc, queries);
    }

    @Test
    void emptyLoadedSelectionWithTotalFirstUsesNumericZeroAndKeepsLabelSeparate() throws Exception {
        var request = request("tickets", null, null, null, List.of(), List.of(column("total", "Total")));

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(request, authentication("VENTA"))))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(5);
            assertThat(sheet.getRow(5).getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(5).getCell(0).getNumericCellValue()).isZero();
            assertText(sheet.getRow(5).getCell(1), "Total documentos");
            assertTotalStyle(sheet.getRow(5).getCell(0));
        }
        verifyNoInteractions(jdbc, queries);
    }

    @Test
    void reorderedTotalSumsPersistedDecimalsAndNegativeDocumentsWithAnExactCachedResult() throws Exception {
        // base/tax intentionally differ from total: the export must use the persisted total.
        var rows = List.of(row("T-1", "0.10"), row("T-2", "0.20"), row("T-3", "-0.10"));
        stubRows(rows);
        var request = request("tickets", null, null, null, ids(rows),
                List.of(column("number", "Número"), column("base", "Base"),
                        column("total", "Total"), column("tax", "Impuesto")));

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(request, authentication("VENTA"))))) {
            var sheet = workbook.getSheetAt(0);
            assertFormulaTotal(sheet.getRow(8).getCell(2), "SUM(C6:C8)", "0.20");
            assertTotalStyle(sheet.getRow(8).getCell(2));
            assertThat(sheet.getRow(7).getCell(2).getNumericCellValue()).isEqualTo(-0.10);
            assertThat(sheet.getRow(8).getCell(0).getStringCellValue()).isEqualTo("Total documentos");
        }
    }

    @Test
    void omittedTotalColumnStillIncludesEveryExportedDocumentInTheNumericGrandTotal() throws Exception {
        var rows = List.of(row("T-1", "0.10"), row("T-2", "0.20"), row("T-3", "-1.00"));
        stubRows(rows);
        var request = request("tickets", null, null, null, ids(rows),
                List.of(column("number", "Número"), column("base", "Base"), column("tax", "Impuesto")));

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(request, authentication("VENTA"))))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(4).getLastCellNum()).isEqualTo((short) 3);
            assertText(sheet.getRow(4).getCell(1), "Base");
            assertText(sheet.getRow(8).getCell(0), "Total documentos");
            var total = sheet.getRow(8).getCell(1);
            assertThat(total.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(BigDecimal.valueOf(total.getNumericCellValue())).isEqualByComparingTo("-0.70");
            assertTotalStyle(total);
            assertThat(sheet.getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A5:C8");
        }
    }

    @ParameterizedTest
    @CsvSource({
            "Customer code,Tax ID,Customer name,Grand total",
            "客户代码,税号,客户名称,总计"
    })
    void exportsTheProvidedEnglishAndChineseCustomerAndTotalLabels(
            String codeLabel, String taxIdLabel, String nameLabel, String totalLabel) throws Exception {
        var rows = List.of(row("T-1", "0.10"), row("T-2", "0.20"));
        stubRows(rows);
        when(customer.getFiscalName()).thenReturn("+Cliente literal");
        var original = request("tickets", null, null, null, ids(rows),
                List.of(column("number", "Number"), column("total", "Total")));
        var labels = new CustomerDocumentExportRequest.Labels(original.labels().sheetName(),
                original.labels().types(), original.labels().statuses(), codeLabel, taxIdLabel, nameLabel, totalLabel);
        var request = new CustomerDocumentExportRequest(customerId, "tickets", null, null, null,
                original.documentIds(), original.columns(), labels);

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(request, authentication("VENTA"))))) {
            var sheet = workbook.getSheetAt(0);
            assertText(sheet.getRow(0).getCell(0), codeLabel);
            assertText(sheet.getRow(1).getCell(0), taxIdLabel);
            assertText(sheet.getRow(2).getCell(0), nameLabel);
            assertText(sheet.getRow(2).getCell(1), "+Cliente literal");
            assertText(sheet.getRow(7).getCell(0), totalLabel);
            assertFormulaTotal(sheet.getRow(7).getCell(1), "SUM(B6:B7)", "0.30");
        }
    }

    @ParameterizedTest
    @CsvSource({
            "Filters,Search,Status,From,To,No filters,Confirmed",
            "筛选条件,搜索,状态,开始日期,结束日期,无筛选条件,已确认"
    })
    void exportsLocalizedAppliedFiltersAsTextEvenWhenTheSearchStartsWithAFormula(
            String title, String searchLabel, String statusLabel, String fromLabel,
            String toLabel, String noneLabel, String confirmedLabel) throws Exception {
        var rows = List.of(row("=SUM(1,2)", "0.25"));
        stubRows(rows);
        var filter = new CustomerDocumentExportRequest.Filters(" =SUM(1,2) ", DocumentStatus.CONFIRMADO,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        var statuses = EnumSet.allOf(DocumentStatus.class).stream().collect(Collectors.toMap(
                status -> status, status -> status == DocumentStatus.CONFIRMADO ? confirmedLabel : status.name()));
        var labels = new CustomerDocumentExportRequest.Labels("Documents", Map.of(),
                statuses, null, null, null, null,
                new CustomerDocumentExportRequest.FilterLabels(
                        title, searchLabel, statusLabel, fromLabel, toLabel, noneLabel));
        var request = new CustomerDocumentExportRequest(customerId, "tickets", filter, "date", "asc", null,
                List.of(column("number", "Number"), column("total", "Total")), labels);
        when(queries.findPage(storeId, customerId, EnumSet.of(CommercialDocumentType.TICKET),
                request.queryFilter(), null, 500))
                .thenReturn(new CustomerDocumentReportQueryRepository.Page(ids(rows), null, false));

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(request, authentication("VENTA"))))) {
            var sheet = workbook.getSheetAt(0);
            assertText(sheet.getRow(3).getCell(0), title + ": " + searchLabel + ": =SUM(1,2); "
                    + statusLabel + ": " + confirmedLabel + "; " + fromLabel + ": 01/09/2026; "
                    + toLabel + ": 30/09/2026");
            assertThat(sheet.getMergedRegions()).extracting(region -> region.formatAsString()).contains("A4:B4");
            assertFormulaTotal(sheet.getRow(6).getCell(1), "SUM(B6:B6)", "0.25");
        }
    }

    @Test
    void writesSyntheticPreviewWithAllNineAttributesAndNegativeRectification() throws Exception {
        when(customer.getClientId()).thenReturn("C-001-000042");
        when(customer.getDocumentNumber()).thenReturn("B00123456");
        when(customer.getFiscalName()).thenReturn("COMERCIAL ATLÁNTICO, S.L.");
        var rows = List.of(
                previewRow("FV-2026-000184", CommercialDocumentType.FACTURA_VENTA, DocumentStatus.PAGADO,
                        "100.00", "21.00", "121.00"),
                previewRow("FV-2026-000185", CommercialDocumentType.FACTURA_VENTA, DocumentStatus.PARCIAL,
                        "0.25", "0.05", "0.30"),
                previewRow("RV-2026-000021", CommercialDocumentType.RECTIFICATIVA_VENTA, DocumentStatus.CONFIRMADO,
                        "-20.00", "-4.20", "-24.20"));
        stubRows(rows);
        var columns = List.of(column("number", "Número"), column("date", "Fecha"), column("type", "Tipo"),
                column("status", "Estado"), column("base", "Base"), column("tax", "Impuestos"),
                column("total", "Total"), column("terminal", "Terminal"), column("user", "Usuario"));
        var original = request("invoices", null, "date", "asc", ids(rows), columns);
        var labels = new CustomerDocumentExportRequest.Labels("Documentos de cliente", original.labels().types(),
                original.labels().statuses(), "Código del cliente", "NIF", "Nombre del cliente", "Total documentos");
        var filters = new CustomerDocumentExportRequest.Filters("2026", null,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        var request = new CustomerDocumentExportRequest(customerId, "invoices", filters,
                "date", "asc", null, columns, labels);
        when(queries.findPage(storeId, customerId,
                EnumSet.of(CommercialDocumentType.FACTURA_VENTA, CommercialDocumentType.RECTIFICATIVA_VENTA),
                request.queryFilter(), null, 500))
                .thenReturn(new CustomerDocumentReportQueryRepository.Page(ids(rows), null, false));

        var bytes = service.export(request, authentication("VENTA"));

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(8);
            assertText(sheet.getRow(2).getCell(1), "COMERCIAL ATLÁNTICO, S.L.");
            assertThat(sheet.getRow(4).getLastCellNum()).isEqualTo((short) 9);
            assertText(sheet.getRow(7).getCell(2), "Rectificativa");
            assertThat(sheet.getRow(7).getCell(6).getNumericCellValue()).isEqualTo(-24.20);
            assertFormulaTotal(sheet.getRow(8).getCell(6), "SUM(G6:G8)", "97.10");
        }
        var preview = Path.of("target/customer-documents-export-preview.xlsx");
        Files.createDirectories(preview.getParent());
        Files.write(preview, bytes);
    }

    private CustomerDocumentExportRequest request(String key, CustomerDocumentExportRequest.Filters filters,
            String sortBy, String direction, List<UUID> ids, List<CustomerDocumentExportRequest.Column> columns) {
        return new CustomerDocumentExportRequest(customerId, key, filters, sortBy, direction, ids, columns,
                new CustomerDocumentExportRequest.Labels("客户单据",
                        Map.of(CommercialDocumentType.TICKET, "小票", CommercialDocumentType.FACTURA_VENTA, "Factura",
                                CommercialDocumentType.RECTIFICATIVA_VENTA, "Rectificativa", CommercialDocumentType.ALBARAN_VENTA, "Albarán"),
                        Map.of(DocumentStatus.BORRADOR, "Borrador", DocumentStatus.CONFIRMADO, "Confirmado",
                                DocumentStatus.ANULADO, "Anulado", DocumentStatus.PENDIENTE, "Pendiente",
                                DocumentStatus.PARCIAL, "Parcial", DocumentStatus.PAGADO, "Pagado")));
    }

    private static CustomerDocumentExportRequest.Column column(String key, String label) {
        return new CustomerDocumentExportRequest.Column(key, label);
    }

    private static CustomerDocumentExcelExportService.ExportRow row(String number, String total) {
        return new CustomerDocumentExcelExportService.ExportRow(UUID.randomUUID(), number,
                LocalDate.parse("2026-09-09"), CommercialDocumentType.TICKET, DocumentStatus.CONFIRMADO,
                new BigDecimal("10.00"), new BigDecimal("2.10"), new BigDecimal(total), "Caja 1", "Vendedor");
    }

    private static CustomerDocumentExcelExportService.ExportRow previewRow(String number,
            CommercialDocumentType type, DocumentStatus status, String base, String tax, String total) {
        return new CustomerDocumentExcelExportService.ExportRow(UUID.randomUUID(), number,
                LocalDate.parse("2026-09-09"), type, status, new BigDecimal(base), new BigDecimal(tax),
                new BigDecimal(total), "Caja 1", "Vendedor");
    }

    private static void assertText(XSSFCell cell, String expected) {
        assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
        assertThat(cell.getStringCellValue()).isEqualTo(expected);
    }

    private static void assertFormulaTotal(XSSFCell cell, String formula, String total) {
        assertThat(cell.getCellType()).isEqualTo(CellType.FORMULA);
        assertThat(cell.getCellFormula()).isEqualTo(formula);
        assertThat(cell.getCachedFormulaResultType()).isEqualTo(CellType.NUMERIC);
        assertThat(BigDecimal.valueOf(cell.getNumericCellValue())).isEqualByComparingTo(total);
    }

    private static void assertTotalStyle(XSSFCell cell) {
        var style = cell.getCellStyle();
        assertThat(style.getFillForegroundColor()).isEqualTo(IndexedColors.GREY_25_PERCENT.getIndex());
        assertThat(style.getFillPattern()).isEqualTo(FillPatternType.SOLID_FOREGROUND);
        assertThat(style.getFont().getColor()).isEqualTo(IndexedColors.BLACK.getIndex());
        assertThat(style.getFont().getBold()).isTrue();
        assertThat(style.getBorderTop()).isNotEqualTo(BorderStyle.NONE);
        assertThat(style.getAlignment()).isEqualTo(HorizontalAlignment.RIGHT);
    }

    private static List<UUID> ids(List<CustomerDocumentExcelExportService.ExportRow> rows) {
        return rows.stream().map(CustomerDocumentExcelExportService.ExportRow::id).toList();
    }

    private void stubRows(List<CustomerDocumentExcelExportService.ExportRow> rows) {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), org.mockito.ArgumentMatchers
                .<RowMapper<CustomerDocumentExcelExportService.ExportRow>>any())).thenReturn(rows);
    }

    private static Authentication authentication(String authority) {
        return new UsernamePasswordAuthenticationToken("user", "", List.of(new SimpleGrantedAuthority(authority)));
    }

    private static Set<Path> temporarySheets() throws IOException {
        var directory = Path.of(System.getProperty("java.io.tmpdir"), DefaultTempFileCreationStrategy.POIFILES);
        if (!Files.isDirectory(directory)) return Set.of();
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().startsWith("poi-sxssf-sheet"))
                    .collect(Collectors.toSet());
        }
    }
}
