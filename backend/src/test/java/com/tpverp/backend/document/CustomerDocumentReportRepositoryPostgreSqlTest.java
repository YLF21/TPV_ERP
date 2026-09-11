package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.excel.CustomerDocumentExcelExportService;
import com.tpverp.backend.excel.CustomerDocumentExportRequest;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class, CustomerDocumentReportQueryRepository.class})
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class CustomerDocumentReportRepositoryPostgreSqlTest {

    private static final String URL = System.getenv("TPV_ERP_TEST_DB_URL");
    private static final String USER = System.getenv("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA =
            "customer_document_reports_" + UUID.randomUUID().toString().replace("-", "");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 9);
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-09T10:00:00Z");

    static {
        execute("create schema " + SCHEMA);
    }

    @Autowired private CommercialDocumentRepository documents;
    @Autowired private CustomerDocumentReportQueryRepository orderedReports;
    @Autowired private CustomerRepository customers;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL
                + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch",
                () -> true);
        registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",
                () -> SqlCapture.class.getName());
    }

    @AfterAll
    static void cleanup() {
        execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @Test
    void includesOrdinaryPaidTicketsAllStatesAndInvoicedOriginsWithinCustomerAndStore() {
        var fixture = fixture();
        var expected = new ArrayList<UUID>();
        for (var status : DocumentStatus.values()) {
            expected.add(document(fixture.store(), fixture.customerId(),
                    CommercialDocumentType.TICKET, status, UUID.randomUUID()));
        }
        var invoice = document(fixture.store(), fixture.customerId(),
                CommercialDocumentType.FACTURA_VENTA, DocumentStatus.PAGADO, UUID.randomUUID());
        jdbc.update("insert into documento_relacion(documento_id,origen_id,tipo) values (?,?,'FACTURA_DE')",
                invoice, expected.get(1));
        document(fixture.store(), fixture.otherCustomerId(), CommercialDocumentType.TICKET,
                DocumentStatus.CONFIRMADO, UUID.randomUUID());
        document(fixture.otherStore(), fixture.customerId(), CommercialDocumentType.TICKET,
                DocumentStatus.CONFIRMADO, UUID.randomUUID());

        var result = documents.findCustomerReportDocuments(fixture.store().storeId(),
                fixture.customerId(), EnumSet.of(CommercialDocumentType.TICKET),
                null, null, null, PageRequest.of(0, 50));

        assertThat(result).extracting(CommercialDocument::getId)
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(result).extracting(CommercialDocument::getEstado)
                .containsExactlyInAnyOrder(DocumentStatus.values());
        assertThat(result).allSatisfy(value -> assertThat(value.isCuentaCobrar()).isFalse());
        assertThat(documents.findCustomerReportDocuments(fixture.store().storeId(),
                UUID.randomUUID(), EnumSet.of(CommercialDocumentType.TICKET),
                null, null, null, PageRequest.of(0, 50))).isEmpty();
    }

    @Test
    void appliesSqlLimitBeforeCollectionFetchAndUsesStableUuidCursor() {
        var fixture = fixture();
        var first = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff3");
        var second = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff2");
        var third = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff1");
        for (var id : List.of(first, second, third)) {
            document(fixture.store(), fixture.customerId(), CommercialDocumentType.TICKET,
                    DocumentStatus.CONFIRMADO, id);
        }
        addPaymentsAndLine(fixture, first);
        var types = EnumSet.of(CommercialDocumentType.TICKET);
        SqlCapture.SQL.clear();

        var page = documents.findCustomerReportDocuments(fixture.store().storeId(),
                fixture.customerId(), types, null, null, null, PageRequest.of(0, 2));

        assertThat(page).extracting(CommercialDocument::getId).containsExactly(first, second);
        assertThat(page.getFirst().getPagos()).hasSize(2);
        assertThat(page.getFirst().getLineas()).hasSize(1);
        assertThat(SqlCapture.SQL).hasSize(3);
        assertThat(SqlCapture.SQL.getFirst()).contains("fetch first ? rows only")
                .doesNotContain(" join ");

        var next = documents.findCustomerReportDocuments(fixture.store().storeId(),
                fixture.customerId(), types, DATE, OCCURRED_AT, second.toString(), PageRequest.of(0, 2));
        assertThat(next).extracting(CommercialDocument::getId).containsExactly(third);
        assertThat(documents.findCustomerReportDocuments(fixture.store().storeId(),
                fixture.otherCustomerId(), types, DATE, OCCURRED_AT, second.toString(),
                PageRequest.of(0, 2))).isEmpty();
    }

    @Test
    void preservesSalesInvoiceRectificationAndDeliveryNoteTypeGroups() {
        var fixture = fixture();
        var invoice = document(fixture.store(), fixture.customerId(),
                CommercialDocumentType.FACTURA_VENTA, DocumentStatus.PENDIENTE, UUID.randomUUID());
        var rectification = document(fixture.store(), fixture.customerId(),
                CommercialDocumentType.RECTIFICATIVA_VENTA, DocumentStatus.ANULADO, UUID.randomUUID());
        var delivery = document(fixture.store(), fixture.customerId(),
                CommercialDocumentType.ALBARAN_VENTA, DocumentStatus.BORRADOR, UUID.randomUUID());

        assertThat(documents.findCustomerReportDocuments(fixture.store().storeId(),
                fixture.customerId(), EnumSet.of(CommercialDocumentType.FACTURA_VENTA,
                        CommercialDocumentType.RECTIFICATIVA_VENTA), null, null, null,
                PageRequest.of(0, 50))).extracting(CommercialDocument::getId)
                .containsExactlyInAnyOrder(invoice, rectification);
        assertThat(documents.findCustomerReportDocuments(fixture.store().storeId(),
                fixture.customerId(), EnumSet.of(CommercialDocumentType.ALBARAN_VENTA),
                null, null, null, PageRequest.of(0, 50))).extracting(CommercialDocument::getId)
                .containsExactly(delivery);
    }

    static Stream<Arguments> globalOrders() {
        return Stream.of(
                Arguments.of("number", "asc", List.of(2, 5, 1, 3, 4)),
                Arguments.of("number", "desc", List.of(3, 1, 5, 2, 4)),
                Arguments.of("date", "asc", List.of(4, 2, 5, 3, 1)),
                Arguments.of("date", "desc", List.of(1, 3, 5, 2, 4)),
                Arguments.of("type", "asc", List.of(3, 2, 5, 4, 1)),
                Arguments.of("type", "desc", List.of(1, 4, 5, 2, 3)),
                Arguments.of("status", "asc", List.of(2, 4, 1, 3, 5)),
                Arguments.of("status", "desc", List.of(5, 3, 1, 4, 2)),
                Arguments.of("base", "asc", List.of(2, 4, 5, 1, 3)),
                Arguments.of("base", "desc", List.of(3, 1, 5, 4, 2)),
                Arguments.of("tax", "asc", List.of(3, 1, 4, 2, 5)),
                Arguments.of("tax", "desc", List.of(5, 2, 4, 1, 3)),
                Arguments.of("total", "asc", List.of(4, 2, 5, 1, 3)),
                Arguments.of("total", "desc", List.of(3, 1, 5, 2, 4)),
                Arguments.of("terminal", "asc", List.of(2, 5, 1, 3, 4)),
                Arguments.of("terminal", "desc", List.of(3, 1, 5, 2, 4)),
                Arguments.of("user", "asc", List.of(2, 5, 1, 3, 4)),
                Arguments.of("user", "desc", List.of(3, 1, 5, 2, 4)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("globalOrders")
    void ordersEveryColumnAcrossCursorPagesWithNullsLast(String column, String direction,
            List<Integer> expected) {
        var fixture = orderedFixture();
        var filter = new CustomerDocumentReportFilter(null, null, null, null, column, direction);
        var ids = new ArrayList<UUID>();
        String cursor = null;
        do {
            var page = orderedReports.findPage(fixture.store().storeId(), fixture.customerId(),
                    EnumSet.allOf(CommercialDocumentType.class), filter, cursor, 2);
            assertThat(page.ids()).hasSizeLessThanOrEqualTo(2);
            ids.addAll(page.ids());
            assertThat(ids).hasSizeLessThanOrEqualTo(5);
            cursor = page.nextCursor();
            assertThat(page.hasMore()).isEqualTo(cursor != null);
        } while (cursor != null);
        assertThat(ids).containsExactlyElementsOf(expected.stream().map(index -> new UUID(0, index)).toList())
                .doesNotHaveDuplicates();
    }

    @Test
    void appliesLiteralNumberStatusAndInclusiveDatesBeforePagingTheWholeHistory() {
        var fixture = fixture();
        for (int index = 0; index < 60; index++) {
            document(fixture.store(), fixture.customerId(), CommercialDocumentType.TICKET,
                    DocumentStatus.CONFIRMADO, UUID.randomUUID());
        }
        var first = filteredDocument(fixture.store(), fixture.customerId(), "FV-%_TARGET-A", "2026-09-01");
        var last = filteredDocument(fixture.store(), fixture.customerId(), "FV-%_TARGET-B", "2026-09-09");
        filteredDocument(fixture.store(), fixture.customerId(), "FV-%_TARGET-EARLY", "2026-08-31");
        filteredDocument(fixture.store(), fixture.customerId(), "FV-%_TARGET-LATE", "2026-09-10");
        filteredDocument(fixture.store(), fixture.customerId(), "FV-X_TARGET-WILDCARD", "2026-09-05");
        filteredDocument(fixture.store(), fixture.otherCustomerId(), "FV-%_TARGET-OTHER", "2026-09-05");
        filteredDocument(fixture.otherStore(), fixture.customerId(), "FV-%_TARGET-STORE", "2026-09-05");
        var wrongStatus = filteredDocument(fixture.store(), fixture.customerId(), "FV-%_TARGET-STATE", "2026-09-05");
        jdbc.update("update documento set estado='PENDIENTE' where id=?", wrongStatus);
        var filter = new CustomerDocumentReportFilter(" %_target ", DocumentStatus.PAGADO,
                DATE.minusDays(8), DATE, "number", "asc");
        var types = EnumSet.of(CommercialDocumentType.TICKET, CommercialDocumentType.FACTURA_VENTA);

        var page = orderedReports.findPage(fixture.store().storeId(), fixture.customerId(), types, filter, null, 1);
        assertThat(page.ids()).containsExactly(first);
        assertThat(page.hasMore()).isTrue();
        var next = orderedReports.findPage(fixture.store().storeId(), fixture.customerId(), types,
                filter, page.nextCursor(), 1);
        assertThat(next.ids()).containsExactly(last);
        assertThat(next.hasMore()).isFalse();
    }

    @Test
    void rejectsCursorsFromAnotherCustomerStoreTypeFilterOrOrder() {
        var fixture = orderedFixture();
        var filter = new CustomerDocumentReportFilter(null, null, null, null, "total", "asc");
        var types = EnumSet.allOf(CommercialDocumentType.class);
        var page = orderedReports.findPage(fixture.store().storeId(), fixture.customerId(), types, filter, null, 2);
        assertThat(page.hasMore()).isTrue();
        assertThatThrownBy(() -> orderedReports.findPage(fixture.store().storeId(), fixture.otherCustomerId(),
                types, filter, page.nextCursor(), 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orderedReports.findPage(fixture.otherStore().storeId(), fixture.customerId(),
                types, filter, page.nextCursor(), 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orderedReports.findPage(fixture.store().storeId(), fixture.customerId(),
                EnumSet.of(CommercialDocumentType.TICKET), filter, page.nextCursor(), 2))
                .isInstanceOf(IllegalArgumentException.class);
        for (var changed : List.of(
                new CustomerDocumentReportFilter("A", null, null, null, "total", "asc"),
                new CustomerDocumentReportFilter(null, DocumentStatus.PAGADO, null, null, "total", "asc"),
                new CustomerDocumentReportFilter(null, null, DATE, DATE, "total", "asc"),
                new CustomerDocumentReportFilter(null, null, null, null, "number", "asc"),
                new CustomerDocumentReportFilter(null, null, null, null, "total", "desc"))) {
            assertThatThrownBy(() -> orderedReports.findPage(fixture.store().storeId(), fixture.customerId(),
                    types, changed, page.nextCursor(), 2)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void keepsGlobalAdminAttributionButSuppressesUsersFromAnotherStore() {
        var fixture = orderedFixture();
        jdbc.update("update usuario set tienda_id=null where id=?", fixture.store().userId());
        orderedDocument(fixture, 6, "GLOBAL", DATE, CommercialDocumentType.TICKET,
                DocumentStatus.CONFIRMADO, 4, 2, fixture.store().userId(), null);

        var page = orderedReports.findPage(fixture.store().storeId(), fixture.customerId(),
                EnumSet.allOf(CommercialDocumentType.class),
                new CustomerDocumentReportFilter(null, null, null, null, "user", "asc"), null, 10);

        assertThat(page.ids()).containsExactly(new UUID(0, 6), new UUID(0, 2), new UUID(0, 5),
                new UUID(0, 1), new UUID(0, 3), new UUID(0, 4));
    }

    @Test
    void exportsRealScopedProjectionAndFilteredOrderAsTypedXlsx() throws Exception {
        var fixture = orderedFixture();
        jdbc.update("update cliente set nombre_fiscal=?, numero_documento=? where id=?",
                "Cliente de la ficha BD", "00123456A", fixture.customerId());
        jdbc.update("update documento set base_total=-2, impuesto_total=-3, total=-5 where id=?", new UUID(0, 4));
        var organization = mock(CurrentOrganization.class);
        var store = mock(Store.class);
        var company = mock(Company.class);
        when(store.getId()).thenReturn(fixture.store().storeId());
        when(company.getId()).thenReturn(fixture.companyId());
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        var service = new CustomerDocumentExcelExportService(organization, customers, orderedReports,
                new NamedParameterJdbcTemplate(jdbc), mock(AuditService.class));
        var authentication = new UsernamePasswordAuthenticationToken("sale", "unused",
                List.of(new SimpleGrantedAuthority("VENTA")));
        var columns = List.of(new CustomerDocumentExportRequest.Column("number", "Número"),
                new CustomerDocumentExportRequest.Column("date", "Fecha"),
                new CustomerDocumentExportRequest.Column("type", "Tipo"),
                new CustomerDocumentExportRequest.Column("status", "Estado"),
                new CustomerDocumentExportRequest.Column("base", "Base"),
                new CustomerDocumentExportRequest.Column("tax", "Impuestos"),
                new CustomerDocumentExportRequest.Column("total", "Total"),
                new CustomerDocumentExportRequest.Column("terminal", "Terminal"),
                new CustomerDocumentExportRequest.Column("user", "Usuario"));
        var labels = new CustomerDocumentExportRequest.Labels("Documentos de cliente",
                Map.of(CommercialDocumentType.FACTURA_VENTA, "Factura",
                        CommercialDocumentType.RECTIFICATIVA_VENTA, "Rectificativa"),
                Map.of(DocumentStatus.BORRADOR, "Borrador", DocumentStatus.CONFIRMADO, "Confirmado",
                        DocumentStatus.ANULADO, "Anulado", DocumentStatus.PENDIENTE, "Pendiente",
                        DocumentStatus.PARCIAL, "Parcial", DocumentStatus.PAGADO, "Pagado"));
        var request = new CustomerDocumentExportRequest(fixture.customerId(), "invoices", null,
                "number", "asc", List.of(new UUID(0, 5), new UUID(0, 4), new UUID(0, 2)), columns, labels);
        var bytes = service.export(request, authentication);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(8);
            assertThat(sheet.getRow(0).getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("C-001-000001");
            assertThat(sheet.getRow(1).getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("00123456A");
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("Cliente de la ficha BD");
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("Filtros: Sin filtros");
            assertThat(sheet.getRow(5).getCell(0).getStringCellValue()).isEqualTo("a");
            assertThat(sheet.getRow(5).getCell(1).getLocalDateTimeCellValue().toLocalDate())
                    .isEqualTo(DATE.minusDays(2));
            assertThat(sheet.getRow(5).getCell(2).getStringCellValue()).isEqualTo("Factura");
            assertThat(sheet.getRow(5).getCell(3).getStringCellValue()).isEqualTo("Parcial");
            assertThat(sheet.getRow(5).getCell(4).getNumericCellValue()).isEqualTo(2);
            assertThat(sheet.getRow(5).getCell(5).getNumericCellValue()).isEqualTo(8);
            assertThat(sheet.getRow(5).getCell(6).getNumericCellValue()).isEqualTo(10);
            assertThat(sheet.getRow(5).getCell(7).getStringCellValue()).isEqualTo("Alfa");
            assertThat(sheet.getRow(5).getCell(8).getStringCellValue()).isEqualTo("Alfa");
            assertThat(sheet.getRow(6).getCell(6).getNumericCellValue()).isEqualTo(-5);
            assertThat(sheet.getRow(6).getCell(7).getStringCellValue()).isEqualTo("—");
            assertThat(sheet.getRow(6).getCell(8).getStringCellValue()).isEqualTo("—");
            assertThat(sheet.getRow(7).getCell(0).getStringCellValue()).isEqualTo("A");
            assertThat(sheet.getRow(8).getCell(0).getStringCellValue()).isEqualTo("Total documentos");
            var total = sheet.getRow(8).getCell(6);
            assertThat(total.getCellFormula()).isEqualTo("SUM(G6:G8)");
            assertThat(total.getCachedFormulaResultType()).isEqualTo(CellType.NUMERIC);
            assertThat(BigDecimal.valueOf(total.getNumericCellValue())).isEqualByComparingTo("15.00");
            assertThat(sheet.getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A5:I8");
        }
        var filtered = new CustomerDocumentExportRequest(fixture.customerId(), "invoices",
                new CustomerDocumentExportRequest.Filters("a", null, null, null),
                "number", "desc", null, columns, labels);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(filtered, authentication)))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(7);
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("Filtros: Búsqueda: a");
            assertThat(sheet.getRow(5).getCell(0).getStringCellValue()).isEqualTo("a");
            assertThat(sheet.getRow(6).getCell(0).getStringCellValue()).isEqualTo("A");
            assertThat(sheet.getRow(7).getCell(6).getCellFormula()).isEqualTo("SUM(G6:G7)");
            assertThat(BigDecimal.valueOf(sheet.getRow(7).getCell(6).getNumericCellValue()))
                    .isEqualByComparingTo("20.00");
        }
        var otherCustomer = document(fixture.store(), fixture.otherCustomerId(),
                CommercialDocumentType.FACTURA_VENTA, DocumentStatus.PAGADO, UUID.randomUUID());
        var otherStore = document(fixture.otherStore(), fixture.customerId(),
                CommercialDocumentType.FACTURA_VENTA, DocumentStatus.PAGADO, UUID.randomUUID());
        for (var unavailable : List.of(otherCustomer, otherStore, new UUID(0, 1), UUID.randomUUID())) {
            var invalid = new CustomerDocumentExportRequest(fixture.customerId(), "invoices", null,
                    null, null, List.of(unavailable), columns, labels);
            assertThatThrownBy(() -> service.export(invalid, authentication))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no están disponibles");
        }
    }

    @Test
    void model347AggregatesAllCompanyStoresAndExcludesOtherCompaniesAndCustomers() {
        var fixture = fixture();
        var otherCompanyId = UUID.randomUUID();
        jdbc.update("insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values (?,?,?,cast(? as jsonb))",
                otherCompanyId, "B00000002", "Otra empresa", address());
        var foreignStore = store(otherCompanyId, "003");
        var foreignCustomer = customer(otherCompanyId, foreignStore.storeId(), "CLIENTE EXTERNO", "C-003-000001");
        model347Document(fixture.store(), fixture.customerId(), CommercialDocumentType.FACTURA_VENTA,
                DocumentStatus.PAGADO, "2026-01-15", "100.00", "21.00");
        model347Document(fixture.otherStore(), fixture.customerId(), CommercialDocumentType.FACTURA_VENTA,
                DocumentStatus.PENDIENTE, "2026-03-15", "200.00", "42.00");
        model347Document(fixture.store(), fixture.otherCustomerId(), CommercialDocumentType.FACTURA_VENTA,
                DocumentStatus.PAGADO, "2026-01-15", "1000.00", "210.00");
        model347Document(fixture.otherStore(), fixture.otherCustomerId(), CommercialDocumentType.FACTURA_VENTA,
                DocumentStatus.PAGADO, "2026-01-15", "2000.00", "420.00");
        model347Document(foreignStore, fixture.customerId(), CommercialDocumentType.FACTURA_VENTA,
                DocumentStatus.PAGADO, "2026-01-15", "4000.00", "840.00");
        model347Document(foreignStore, foreignCustomer, CommercialDocumentType.FACTURA_VENTA,
                DocumentStatus.PAGADO, "2026-01-15", "8000.00", "1680.00");
        var repository = new CustomerModel347Repository(new NamedParameterJdbcTemplate(jdbc));

        var quarters = repository.quarterTotals(fixture.companyId(), fixture.customerId(), 2026);

        assertThat(quarters).singleElement().satisfies(quarter -> assertModel347Quarter(quarter, 1, "363.00", 2));
        assertThat(repository.quarterTotals(fixture.companyId(), foreignCustomer, 2026)).isEmpty();
        assertThat(repository.quarterTotals(fixture.companyId(), UUID.randomUUID(), 2026)).isEmpty();
        assertThat(repository.quarterTotals(UUID.randomUUID(), fixture.customerId(), 2026)).isEmpty();
    }

    @Test
    void model347IncludesInvoicesAndRectificationsInEverySupportedStateOnly() {
        var fixture = fixture();
        var supportedStates = List.of(DocumentStatus.CONFIRMADO, DocumentStatus.PENDIENTE,
                DocumentStatus.PARCIAL, DocumentStatus.PAGADO);
        var supportedTypes = EnumSet.of(CommercialDocumentType.FACTURA_VENTA,
                CommercialDocumentType.RECTIFICATIVA_VENTA);
        for (int index = 0; index < supportedStates.size(); index++) {
            var date = LocalDate.of(2026, index * 3 + 1, 15).toString();
            model347Document(fixture.store(), fixture.customerId(), CommercialDocumentType.FACTURA_VENTA,
                    supportedStates.get(index), date, "100.00", "21.00");
            model347Document(fixture.store(), fixture.customerId(), CommercialDocumentType.RECTIFICATIVA_VENTA,
                    supportedStates.get(index), date, "-10.00", "-2.10");
        }
        for (var type : CommercialDocumentType.values()) {
            for (var status : DocumentStatus.values()) {
                if (!supportedTypes.contains(type) || !supportedStates.contains(status)) {
                    model347Document(fixture.store(), fixture.customerId(), type, status,
                            "2026-01-15", "1000.00", "210.00");
                }
            }
        }
        var repository = new CustomerModel347Repository(new NamedParameterJdbcTemplate(jdbc));

        var quarters = repository.quarterTotals(fixture.companyId(), fixture.customerId(), 2026);

        assertThat(quarters).extracting(CustomerModel347Report.Quarter::number).containsExactly(1, 2, 3, 4);
        for (int index = 0; index < quarters.size(); index++) {
            assertModel347Quarter(quarters.get(index), index + 1, "108.90", 2);
        }
    }

    @Test
    void model347UsesDocumentDatesForQuarterAndAnnualBoundariesIncludingLeapDay() {
        var fixture = fixture();
        var dates = List.of("2024-01-01", "2024-02-29", "2024-03-31", "2024-04-01", "2024-06-30",
                "2024-07-01", "2024-09-30", "2024-10-01", "2024-12-31");
        for (int index = 0; index < dates.size(); index++) {
            model347Document(fixture.store(), fixture.customerId(), CommercialDocumentType.FACTURA_VENTA,
                    DocumentStatus.CONFIRMADO, dates.get(index),
                    new BigDecimal("1.01").multiply(BigDecimal.valueOf(index + 1L)).toPlainString(), "0.00");
        }
        // Confirmation and creation remain in 2026 for these documents dated in 2024.
        for (var date : List.of("2023-12-31", "2025-01-01")) {
            var excluded = model347Document(fixture.store(), fixture.customerId(), CommercialDocumentType.FACTURA_VENTA,
                    DocumentStatus.PAGADO, date, "1000.00", "210.00");
            jdbc.update("update documento set confirmado_en=cast(? as timestamptz) where id=?",
                    "2024-06-15T10:00:00Z", excluded);
        }
        var repository = new CustomerModel347Repository(new NamedParameterJdbcTemplate(jdbc));

        var quarters = repository.quarterTotals(fixture.companyId(), fixture.customerId(), 2024);

        assertThat(quarters).extracting(CustomerModel347Report.Quarter::number).containsExactly(1, 2, 3, 4);
        assertModel347Quarter(quarters.get(0), 1, "6.06", 3);
        assertModel347Quarter(quarters.get(1), 2, "9.09", 2);
        assertModel347Quarter(quarters.get(2), 3, "13.13", 2);
        assertModel347Quarter(quarters.get(3), 4, "17.17", 2);
    }

    @Test
    void model347PreservesSignedTotalsAndCountsZeroValueDocuments() {
        var fixture = fixture();
        model347Document(fixture.store(), fixture.customerId(), CommercialDocumentType.FACTURA_VENTA,
                DocumentStatus.PAGADO, "2026-01-15", "100.00", "21.00");
        model347Document(fixture.store(), fixture.customerId(), CommercialDocumentType.RECTIFICATIVA_VENTA,
                DocumentStatus.CONFIRMADO, "2026-03-15", "-200.00", "-42.00");
        model347Document(fixture.store(), fixture.customerId(), CommercialDocumentType.RECTIFICATIVA_VENTA,
                DocumentStatus.CONFIRMADO, "2026-04-15", "10.00", "2.10");
        for (var type : List.of(CommercialDocumentType.FACTURA_VENTA, CommercialDocumentType.RECTIFICATIVA_VENTA)) {
            model347Document(fixture.store(), fixture.customerId(), type,
                    DocumentStatus.CONFIRMADO, "2026-07-15", "0.00", "0.00");
        }
        var repository = new CustomerModel347Repository(new NamedParameterJdbcTemplate(jdbc));

        var quarters = repository.quarterTotals(fixture.companyId(), fixture.customerId(), 2026);

        assertThat(quarters).extracting(CustomerModel347Report.Quarter::number).containsExactly(1, 2, 3);
        assertModel347Quarter(quarters.get(0), 1, "-121.00", 2);
        assertModel347Quarter(quarters.get(1), 2, "12.10", 1);
        assertModel347Quarter(quarters.get(2), 3, "0.00", 2);
        assertThat(repository.quarterTotals(fixture.companyId(), fixture.customerId(), 2025)).isEmpty();
    }

    @Test
    void model347DoesNotMultiplyDocumentTotalsForSeveralPaymentsAndLines() {
        var fixture = fixture();
        var invoice = model347Document(fixture.store(), fixture.customerId(), CommercialDocumentType.FACTURA_VENTA,
                DocumentStatus.PAGADO, "2026-01-15", "20.00", "4.20");
        addPaymentsAndLine(fixture, invoice);
        jdbc.update("update documento_pago set importe=12.10 where documento_id=?", invoice);
        jdbc.update("""
                update documento_linea set precio_unitario=12.10,porcentaje_impuesto=21,
                    base=10,impuesto=2.10,total=12.10 where documento_id=?
                """, invoice);
        jdbc.update("""
                insert into documento_linea(id,documento_id,producto_id,tipo_linea,posicion,cantidad,
                    codigo,nombre,tarifa,precio_unitario,descuento,impuestos_incluidos,
                    regimen_impuesto,porcentaje_impuesto,base,impuesto,total)
                select ?,documento_id,producto_id,tipo_linea,2,cantidad,
                    codigo,nombre,tarifa,precio_unitario,descuento,impuestos_incluidos,
                    regimen_impuesto,porcentaje_impuesto,base,impuesto,total
                from documento_linea where documento_id=? and posicion=1
                """, UUID.randomUUID(), invoice);
        model347Document(fixture.store(), fixture.customerId(), CommercialDocumentType.FACTURA_VENTA,
                DocumentStatus.PENDIENTE, "2026-02-15", "20.00", "4.20");
        var repository = new CustomerModel347Repository(new NamedParameterJdbcTemplate(jdbc));

        var quarters = repository.quarterTotals(fixture.companyId(), fixture.customerId(), 2026);

        assertThat(quarters).singleElement().satisfies(quarter -> assertModel347Quarter(quarter, 1, "48.40", 2));
    }

    @Test
    void model347AggregatesMoreThanFiveHundredDocumentsWithoutTruncation() {
        var fixture = fixture();
        var rows = new ArrayList<Object[]>();
        for (int index = 0; index < 602; index++) {
            rows.add(new Object[] {UUID.randomUUID(), fixture.store().storeId(), fixture.store().warehouseId(),
                    fixture.customerId(), LocalDate.of(2026, index < 601 ? 1 : 10, 15),
                    OCCURRED_AT.toString(), fixture.store().userId()});
        }
        jdbc.batchUpdate("""
                insert into documento(id,tienda_id,almacen_id,cliente_id,tipo,estado,fecha,
                    creado_en,creado_por,base_total,impuesto_total,total)
                values (?,?,?,?,'FACTURA_VENTA','CONFIRMADO',?,cast(? as timestamptz),?,1,0.21,1.21)
                """, rows);
        var repository = new CustomerModel347Repository(new NamedParameterJdbcTemplate(jdbc));

        var quarters = repository.quarterTotals(fixture.companyId(), fixture.customerId(), 2026);

        assertThat(quarters).extracting(CustomerModel347Report.Quarter::number).containsExactly(1, 4);
        assertModel347Quarter(quarters.get(0), 1, "727.21", 601);
        assertModel347Quarter(quarters.get(1), 4, "1.21", 1);
    }

    private UUID model347Document(StoreFixture store, UUID customerId, CommercialDocumentType type,
            DocumentStatus status, String date, String base, String tax) {
        var id = document(store, customerId, type, status, UUID.randomUUID());
        var baseAmount = new BigDecimal(base);
        var taxAmount = new BigDecimal(tax);
        jdbc.update("update documento set fecha=cast(? as date),base_total=?,impuesto_total=?,total=? where id=?",
                date, baseAmount, taxAmount, baseAmount.add(taxAmount), id);
        return id;
    }

    private static void assertModel347Quarter(CustomerModel347Report.Quarter quarter,
            int number, String total, long documentCount) {
        assertThat(quarter.number()).isEqualTo(number);
        assertThat(quarter.total()).isEqualByComparingTo(total);
        assertThat(quarter.documentCount()).isEqualTo(documentCount);
    }

    private Fixture orderedFixture() {
        var fixture = fixture();
        var betaUser = namedUser(fixture.store(), "Beta");
        var alphaUser = namedUser(fixture.store(), "Alfa");
        var gammaUser = namedUser(fixture.store(), "Gamma");
        var betaTerminal = terminal(fixture.store(), "Beta");
        var alphaTerminal = terminal(fixture.store(), "Alfa");
        var gammaTerminal = terminal(fixture.store(), "Gamma");
        orderedDocument(fixture, 1, "B", DATE, CommercialDocumentType.TICKET,
                DocumentStatus.CONFIRMADO, 10, 3, betaUser, betaTerminal);
        orderedDocument(fixture, 2, "A", DATE.minusDays(2), CommercialDocumentType.FACTURA_VENTA,
                DocumentStatus.ANULADO, 2, 8, alphaUser, alphaTerminal);
        orderedDocument(fixture, 3, "C", DATE.minusDays(1), CommercialDocumentType.ALBARAN_VENTA,
                DocumentStatus.PAGADO, 50, 1, gammaUser, gammaTerminal);
        orderedDocument(fixture, 4, null, DATE.minusDays(3), CommercialDocumentType.RECTIFICATIVA_VENTA,
                DocumentStatus.BORRADOR, 2, 3, fixture.otherStore().userId(), null);
        orderedDocument(fixture, 5, "a", DATE.minusDays(2), CommercialDocumentType.FACTURA_VENTA,
                DocumentStatus.PARCIAL, 2, 8, alphaUser, alphaTerminal);
        return fixture;
    }

    private void orderedDocument(Fixture fixture, int index, String number, LocalDate date,
            CommercialDocumentType type, DocumentStatus status, int base, int tax, UUID actor, UUID terminal) {
        jdbc.update("""
                insert into documento(id,tienda_id,almacen_id,cliente_id,tipo,estado,numero,fecha,
                    creado_en,creado_por,base_total,impuesto_total,total,terminal_origen_id)
                values (?,?,?,?,?,?,?,?,cast(? as timestamptz),?,?,?,?,?)
                """, new UUID(0, index), fixture.store().storeId(), fixture.store().warehouseId(),
                fixture.customerId(), type.name(), status.name(), number, date, OCCURRED_AT.toString(),
                actor, base, tax, base + tax, terminal);
    }

    private UUID namedUser(StoreFixture store, String name) {
        var id = UUID.randomUUID();
        jdbc.update("""
                insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id)
                select ?, ?, ?, ?, 'hash', rol_id from usuario where id=?
                """, id, store.storeId(), name.toUpperCase(java.util.Locale.ROOT), name, store.userId());
        return id;
    }

    private UUID terminal(StoreFixture store, String name) {
        var id = UUID.randomUUID();
        jdbc.update("insert into terminal(id,tienda_id,nombre,tipo,credential_hash) values (?,?,?,'TERMINAL_VENTA','hash')",
                id, store.storeId(), name);
        return id;
    }

    private UUID filteredDocument(StoreFixture store, UUID customerId, String number, String date) {
        var id = document(store, customerId, CommercialDocumentType.FACTURA_VENTA,
                DocumentStatus.PAGADO, UUID.randomUUID());
        jdbc.update("update documento set numero=?,fecha=cast(? as date) where id=?", number, date, id);
        return id;
    }

    private Fixture fixture() {
        var companyId = UUID.randomUUID();
        jdbc.update("insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values (?,?,?,cast(? as jsonb))",
                companyId, "B00000001", "Test", address());
        var primary = store(companyId, "001");
        return new Fixture(companyId, primary, store(companyId, "002"),
                customer(companyId, primary.storeId(), "CLIENTE 1", "C-001-000001"),
                customer(companyId, primary.storeId(), "CLIENTE 2", "C-001-000002"));
    }

    private StoreFixture store(UUID companyId, String code) {
        var storeId = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var warehouseId = UUID.randomUUID();
        jdbc.update("""
                insert into tienda(id,empresa_id,nombre,direccion,address_normalized_hash,
                    timezone,moneda,locale,codigo_tienda)
                values (?,?,?,cast(? as jsonb),?,'Atlantic/Canary','EUR','es-ES',?)
                """, storeId, companyId, "T " + code, address(), "hash-" + code, code);
        jdbc.update("insert into rol(id,tienda_id,nombre,protegido) values (?,?,'ADMIN',true)", roleId, storeId);
        jdbc.update("""
                insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id,protegido)
                values (?,?,'ADMIN','ADMIN','hash',?,true)
                """, userId, storeId, roleId);
        jdbc.update("insert into almacen(id,tienda_id,nombre,predeterminado) values (?,?,'GENERAL',true)",
                warehouseId, storeId);
        return new StoreFixture(storeId, warehouseId, userId);
    }

    private UUID customer(UUID companyId, UUID storeId, String name, String code) {
        var id = UUID.randomUUID();
        jdbc.update("""
                insert into cliente(id,empresa_id,client_id,client_code_store_id,
                    nombre_fiscal,tipo_documento,numero_documento)
                values (?, ?, ?, ?, ?, 'OTRO', ?)
                """, id, companyId, code, storeId, name, name);
        return id;
    }

    private UUID document(StoreFixture store, UUID customerId, CommercialDocumentType type,
            DocumentStatus status, UUID id) {
        jdbc.update("""
                insert into documento(id,tienda_id,almacen_id,cliente_id,tipo,estado,numero,fecha,
                    creado_en,confirmado_en,creado_por,confirmado_por,base_total,total,cuenta_cobrar)
                values (?,?,?,?,?,?,?,? ,cast(? as timestamptz),cast(? as timestamptz),?,?,10,10,false)
                """, id, store.storeId(), store.warehouseId(), customerId, type.name(), status.name(),
                "D-" + id.toString().replace("-", "").substring(8), DATE, OCCURRED_AT.toString(),
                status == DocumentStatus.BORRADOR ? null : OCCURRED_AT.toString(), store.userId(),
                status == DocumentStatus.BORRADOR ? null : store.userId());
        return id;
    }

    private void addPaymentsAndLine(Fixture fixture, UUID documentId) {
        var methodId = UUID.randomUUID();
        jdbc.update("insert into metodo_pago(id,empresa_id,nombre) values (?,?,'EFECTIVO')",
                methodId, fixture.companyId());
        for (var position : List.of(1, 2)) {
            jdbc.update("""
                    insert into documento_pago(id,documento_id,metodo_pago_id,posicion,importe,principal,creado_en)
                    values (?,?,?,?,5,?,now())
                    """, UUID.randomUUID(), documentId, methodId, position, position == 1);
        }
        var familyId = UUID.randomUUID();
        var taxId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        jdbc.update("insert into familia(id,tienda_id,nombre) values (?,?,'GENERAL')",
                familyId, fixture.store().storeId());
        jdbc.update("insert into impuesto_tienda(id,tienda_id,porcentaje) values (?,?,0)",
                taxId, fixture.store().storeId());
        jdbc.update("insert into producto(id,tienda_id,familia_id,impuesto_id,nombre) values (?,?,?,?,'Producto')",
                productId, fixture.store().storeId(), familyId, taxId);
        jdbc.update("""
                insert into documento_linea(id,documento_id,producto_id,tipo_linea,posicion,cantidad,
                    codigo,nombre,tarifa,precio_unitario,descuento,impuestos_incluidos,
                    regimen_impuesto,porcentaje_impuesto,base,impuesto,total)
                values (?,?,?,'PRODUCT',1,1,'P1','Producto','VENTA',10,0,true,'IVA',0,10,0,10)
                """, UUID.randomUUID(), documentId, productId);
    }

    private static String address() {
        return "{\"linea1\":\"CALLE TEST\",\"ciudad\":\"LAS PALMAS\","
                + "\"codigoPostal\":\"35001\",\"provincia\":\"LAS PALMAS\",\"pais\":\"ES\"}";
    }

    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo preparar PostgreSQL de pruebas", exception);
        }
    }

    public static class SqlCapture implements StatementInspector {
        static final List<String> SQL = new CopyOnWriteArrayList<>();

        @Override
        public String inspect(String sql) {
            SQL.add(sql);
            return sql;
        }
    }

    private record StoreFixture(UUID storeId, UUID warehouseId, UUID userId) {
    }

    private record Fixture(UUID companyId, StoreFixture store, StoreFixture otherStore,
            UUID customerId, UUID otherCustomerId) {
    }
}
