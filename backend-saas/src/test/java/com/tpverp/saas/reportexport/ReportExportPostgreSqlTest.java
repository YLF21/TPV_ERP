package com.tpverp.saas.reportexport;

import static com.tpverp.saas.reportexport.ReportExportService.ANALYTICS_PAGE_SIZE;
import static com.tpverp.saas.reportexport.ReportExportService.INVENTORY_PAGE_SIZE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tpverp.saas.admin.CompanySummaryResponse;
import com.tpverp.saas.admin.SaasAdminUser;
import com.tpverp.saas.admin.SaasAdminUserRepository;
import com.tpverp.saas.admin.SaasSessionTokenStore;
import com.tpverp.saas.analytics.ArticleReport;
import com.tpverp.saas.analytics.ArticleReportService;
import com.tpverp.saas.analytics.DocumentReport;
import com.tpverp.saas.analytics.DocumentReportService;
import com.tpverp.saas.analytics.ReportStatistics;
import com.tpverp.saas.analytics.ReportStatisticsService;
import com.tpverp.saas.inventory.InventoryReport;
import com.tpverp.saas.inventory.InventoryReportService;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {"spring.flyway.default-schema=report_export_test",
        "spring.datasource.hikari.schema=report_export_test",
        "spring.jpa.properties.hibernate.default_schema=report_export_test"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ReportExportPostgreSqlTest {
    private static final String PATH = "/api/v1/admin/reports/export/";
    private static final Instant NOW = Instant.parse("2025-02-01T00:00:00Z");
    private static final ReportStatistics.Period PERIOD = new ReportStatistics.Period(
            LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-04"), 4);
    private static final ReportStatistics.Coverage COVERAGE = new ReportStatistics.Coverage(0, 0, null);
    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID STORE = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SaasAdminUserRepository admins;
    @Autowired SaasSessionTokenStore sessions;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @MockitoBean ArticleReportService articles;
    @MockitoBean DocumentReportService documents;
    @MockitoBean ReportStatisticsService statistics;
    @MockitoBean InventoryReportService inventory;

    private Set<Path> before;

    private static final String DIRECTORIES = "/api/v1/admin/directories/export/";

    @Test
    void allStaffCanDownloadDirectoriesIncludingCompanyPost() throws Exception {
        String tenant = "Bearer " + sessions.issue("tenant", "directory-tenant").token();
        String reportsOnly = adminToken("VIEW_REPORTS");
        String readOnly = adminToken("VIEW_ADMIN_DATA");
        for (String name : List.of("companies", "stores", "licenses")) {
            var path = DIRECTORIES + name;
            java.util.function.Supplier<MockHttpServletRequestBuilder> request = () -> name.equals("companies")
                    ? post(path).contentType("application/json")
                            .content("{\"companyIds\":[],\"sortBy\":\"companyName\",\"sortDirection\":\"ASC\"}")
                    : get(path).param("companyId", UUID.randomUUID().toString());
            mvc.perform(request.get()).andExpect(status().isUnauthorized());
            mvc.perform(request.get().header("Authorization", tenant)).andExpect(status().isUnauthorized());
            mvc.perform(request.get().header("Authorization", reportsOnly)).andExpect(status().isOk());
            var empty = workbook(request.get().param("language", "zh"), readOnly, name);
            assertThat(empty.get("xl/workbook.xml")).contains("数据", "上下文");
            assertThat(empty.get("xl/worksheets/sheet1.xml")).contains("frozen")
                    .doesNotContain("<row r=\"2\"");
            assertThat(empty.get("xl/worksheets/sheet2.xml")).contains("行数", ">0<", "UTC");
        }
    }

    @Test
    void companyExportReadsOnlyRequestedProfilesInRequestOrderAndValidatesSelection() throws Exception {
        UUID first = company("=Formula test");
        UUID second = company("Second company");
        UUID excluded = company("Excluded company");
        String readOnly = adminToken("VIEW_ADMIN_DATA");
        var selection = new DirectoryExportService.CompanySelection(List.of(second, first),
                Map.of("contactEmail", "test@example.org"), "companyName", "DESC");
        var sheets = workbook(companiesRequest(mapper.writeValueAsString(selection)), readOnly, "companies");
        String data = sheets.get("xl/worksheets/sheet1.xml");
        assertThat(data).contains("A1:L3", "=Formula test", "Second company", "<c r=\"E2\" s=\"2\"/>")
                .doesNotContain("<f>", "Excluded company", excluded.toString(), first.toString(), "test@example.org");
        assertThat(data.indexOf("Second company")).isLessThan(data.indexOf("=Formula test"));
        assertThat(sheets.get("xl/worksheets/sheet2.xml")).contains("test@example.org", "Descendente");
        assertThat(sheets.get("xl/styles.xml")).contains("FF0B2A4C", "FFF0F4F8", "style=\"thin\"");
        for (String body : List.of("{}", "{\"companyIds\":null}",
                mapper.writeValueAsString(new DirectoryExportService.CompanySelection(List.of(first, first),
                        Map.of(), "taxId", "ASC")))) {
            mvc.perform(companiesRequest(body).header("Authorization", readOnly)).andExpect(status().isBadRequest())
                    .andExpect(header().doesNotExist("Content-Disposition"));
        }
        var missing = new DirectoryExportService.CompanySelection(List.of(second, UUID.randomUUID()),
                Map.of(), "taxId", "ASC");
        mvc.perform(companiesRequest(mapper.writeValueAsString(missing)).header("Authorization", readOnly))
                .andExpect(status().isConflict()).andExpect(header().doesNotExist("Content-Disposition"));
        var overflow = new DirectoryExportService.CompanySelection(java.util.Collections.nCopies(50_001, first),
                Map.of(), "taxId", "ASC");
        mvc.perform(companiesRequest(mapper.writeValueAsString(overflow)).header("Authorization", readOnly))
                .andExpect(status().isUnprocessableContent()).andExpect(header().doesNotExist("Content-Disposition"));
    }

    @Test
    void storeAndLicenseDownloadsUseEveryFilteredPageInOrderWithExactCurrencyAndTextCodes() throws Exception {
        UUID company = company("Directory page test");
        enableEnterprisePlan(company);
        jdbc.update("""
                insert into saas_store(id,company_id,code,name,created_at,time_zone_id,tax_regime,
                    commercial_profile,service_price,billing_period,active)
                select gen_random_uuid(),?,lpad(n::text,3,'0'),'Export '||lpad(n::text,3,'0'),now(),'UTC','IVA',
                       'MINORISTA',9007199254740993.01,'MONTHLY', n<102 from generate_series(1,102) n
                """, company);
        jdbc.update("""
                insert into saas_license(id,company_id,store_id,reference,valid_until,status,max_windows,max_pda,created_at)
                select gen_random_uuid(),company_id,id,'DIR-'||code,now()+interval '13 hours',
                    case when active then 'VALIDA' else 'BLOQUEADA_MANUAL' end,1,0,now()
                from saas_store where company_id=?
                """, company);
        jdbc.update("""
                insert into saas_billing_invoice(id,company_id,number,concept,amount,currency,status,issued_at,due_at,
                    created_at,series,fiscal_year,tax_regime,tax_base,tax_rate,tax_amount,fiscal_status)
                select gen_random_uuid(),?,'EXPORT-'||currency,'Test',amount,currency,'PENDIENTE',now(),now(),
                    now(),'EXPORT',2026,'IVA',amount,'0.00','0.00','CALCULATED'
                from (values ('EUR','9007199254740993.02'),('USD','12.34')) d(currency,amount)
                """, company);
        String token = adminToken("VIEW_ADMIN_DATA");
        var stores = workbook(get(DIRECTORIES + "stores").param("companyId", company.toString())
                .param("q", "Export").param("active", "true").param("commercialConfiguration", "pending")
                .param("sortBy", "code").param("sortDirection", "DESC")
                .param("page", "7").param("size", "1").param("language", "en"), token, "stores");
        String data = stores.get("xl/worksheets/sheet1.xml");
        assertThat(data).contains("A1:N102", ">001<", ">9007199254740993.01<", ">Retail<", ">Monthly<",
                ">Export 101<", ">Export 001<").doesNotContain("Export 102", company.toString());
        assertThat(data.indexOf(">Export 101<")).isLessThan(data.indexOf(">Export 001<"));
        assertThat(stores.get("xl/worksheets/sheet2.xml")).contains("Directory page test", "Descending", ">Export<",
                "Commercial configuration", ">Pending<");
        var licenses = workbook(get(DIRECTORIES + "licenses").param("companyId", company.toString())
                .param("q", "DIR-").param("status", "VALIDA").param("hasConnections", "false").param("billingStatus", "PENDIENTE")
                .param("expiresBefore", Instant.now().plusSeconds(2 * 86400).toString())
                .param("sortBy", "reference").param("sortDirection", "DESC")
                .param("size", "1").param("language", "en"), token, "licenses");
        String licenseData = licenses.get("xl/worksheets/sheet1.xml");
        assertThat(licenseData).contains("A1:M102", ">DIR-101<", ">DIR-001<", ">001 · Export 001<",
                ">9007199254740993.02 EUR / 12.34 USD<", ">Valid<", "r=\"F2\" s=\"7\" t=\"n\"><v>1</v>")
                .doesNotContain("DIR-102", company.toString());
        assertThat(licenseData.indexOf(">DIR-101<")).isLessThan(licenseData.indexOf(">DIR-001<"));
        assertThat(licenses.get("xl/worksheets/sheet2.xml")).contains("With active installations", ">No<");
    }

    @Test
    void commercialConfigurationFiltersLegacyNullsButIncludesZeroPriceAndPastValidityAsComplete() throws Exception {
        UUID company = company("Commercial configuration test");
        enableEnterprisePlan(company);
        jdbc.update("""
                insert into saas_store(id,company_id,code,name,created_at,time_zone_id,tax_regime,
                    commercial_profile,service_price,billing_period,valid_until,active)
                values (gen_random_uuid(),?,'001','Zero price',now(),'UTC','IVA','MINORISTA',0,'MONTHLY','2000-01-01',false),
                       (gen_random_uuid(),?,'002','Missing price and period',now(),'UTC','IVA','MINORISTA',null,null,now(),true),
                       (gen_random_uuid(),?,'003','Missing validity',now(),'UTC','IVA','MINORISTA',1,'MONTHLY',null,true),
                       (gen_random_uuid(),?,'004','Missing price and validity',now(),'UTC','IVA','MINORISTA',null,null,null,true)
                """, company, company, company, company);
        String token = adminToken("VIEW_ADMIN_DATA");
        String path = "/api/v1/admin/stores";
        mvc.perform(get(path).header("Authorization", token).param("companyId", company.toString())
                        .param("commercialConfiguration", "pending").param("sortBy", "code")
                        .param("size", "2").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.items[0].code").value("004"));
        mvc.perform(get(path).header("Authorization", token).param("companyId", company.toString())
                        .param("commercialConfiguration", "complete"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Zero price"))
                .andExpect(jsonPath("$.items[0].servicePrice").value(0.00));
        var complete = workbook(get(DIRECTORIES + "stores").param("companyId", company.toString())
                .param("commercialConfiguration", "complete").param("active", "false").param("language", "en"), token, "stores");
        assertThat(complete.get("xl/worksheets/sheet1.xml")).contains(">Zero price<", ">0.00<")
                .doesNotContain("Missing ");
        assertThat(complete.get("xl/worksheets/sheet2.xml")).contains("Commercial configuration", ">Complete<");
        for (String endpoint : List.of(path, DIRECTORIES + "stores")) {
            mvc.perform(get(endpoint).header("Authorization", token).param("commercialConfiguration", "unknown"))
                    .andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Content-Disposition"));
        }
    }

    @Test
    void countsAndExactStoreLicenseFiltersRetainHistoricalLinksAndEveryStatus() throws Exception {
        UUID company = company("Store relation test");
        enableEnterprisePlan(company);
        UUID empty = company("Empty store relation test");
        List<UUID> stores = List.of(UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("""
                insert into saas_store(id,company_id,code,name,created_at,time_zone_id,tax_regime,commercial_profile,active)
                values (?,?,'001','Linked store',now(),'UTC','IVA','MINORISTA',false),
                       (?,?,'002','Other linked store',now(),'UTC','IVA','MINORISTA',true)
                """, stores.get(0), company, stores.get(1), company);
        var linked = stores.getFirst();
        UUID direct = UUID.randomUUID(), paired = UUID.randomUUID(), installed = UUID.randomUUID(), unrelated = UUID.randomUUID();
        jdbc.update("""
                insert into saas_license(id,company_id,store_id,reference,valid_until,status,max_windows,max_pda,created_at)
                values (?,?,?,'LINK-01',now()+interval '1 day','VALIDA',1,0,now()),
                       (?,?,null,'LINK-02',now()+interval '1 day','BLOQUEADA_MANUAL',1,0,now()),
                       (?,?,null,'LINK-03',now()-interval '1 day','VALIDA',1,0,now()),
                       (?,?,null,'LINK-04',now()+interval '1 day','VALIDA',1,0,now())
                """, direct, company, linked, paired, company, installed, company, unrelated, company);
        jdbc.update("""
                insert into saas_pairing_code(id,company_id,store_id,license_id,code,expires_at,consumed_at,created_at)
                values (gen_random_uuid(),?,?,?,'USED-STORE-01',now(),now(),now()),
                       (gen_random_uuid(),?,?,?,'USED-STORE-02',now(),now(),now()),
                       (gen_random_uuid(),?,?,?,'USED-STORE-03',now(),now(),now())
                """, company, linked, paired, company, stores.get(1), paired, company, linked, direct);
        jdbc.update("""
                insert into saas_installation(id,company_id,store_id,license_id,installation_id,
                    installation_reference,token_hash,linked_at,active,revoked_at,revoked_by,revocation_reason)
                values (gen_random_uuid(),?,?,?,gen_random_uuid(),'DIRECTORY-STORE-LINK',?,now(),false,
                    now(),'admin','Historical installation')
                """, company, linked, installed, "a".repeat(64));
        String token = adminToken("VIEW_ADMIN_DATA");
        var companiesResponse = mvc.perform(get("/api/v1/admin/companies").header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var profiles = List.of(mapper.readValue(companiesResponse, CompanySummaryResponse[].class));
        assertThat(profiles).filteredOn(profile -> profile.companyId().equals(company)).singleElement()
                .satisfies(profile -> {
                    assertThat(profile.storeCount()).isEqualTo(2);
                    assertThat(profile.licenseCount()).isEqualTo(4);
                });
        assertThat(profiles).filteredOn(profile -> profile.companyId().equals(empty)).singleElement()
                .satisfies(profile -> {
                    assertThat(profile.storeCount()).isZero();
                    assertThat(profile.licenseCount()).isZero();
                });
        for (String sortBy : List.of("storeCount", "licenseCount")) {
            var selection = new DirectoryExportService.CompanySelection(List.of(company, empty), Map.of(), sortBy, "DESC");
            var sheet = workbook(companiesRequest(mapper.writeValueAsString(selection)), token, "companies")
                    .get("xl/worksheets/sheet1.xml");
            assertThat(sheet).contains(">Tiendas<", ">Licencias<")
                    .containsPattern("r=\"C2\" s=\"[0-9]+\" t=\"n\"><v>2</v>")
                    .containsPattern("r=\"D2\" s=\"[0-9]+\" t=\"n\"><v>4</v>")
                    .containsPattern("r=\"C3\" s=\"[0-9]+\" t=\"n\"><v>0</v>")
                    .containsPattern("r=\"D3\" s=\"[0-9]+\" t=\"n\"><v>0</v>");
        }

        String path = "/api/v1/admin/license-workspace";
        mvc.perform(get(path).header("Authorization", token).param("companyId", company.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(4));
        mvc.perform(get(path).header("Authorization", token).param("storeId", linked.toString())
                        .param("companyId", company.toString()).param("sortBy", "reference"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].reference").value("LINK-01"))
                .andExpect(jsonPath("$.items[0].status").value("VALIDA"))
                .andExpect(jsonPath("$.items[1].reference").value("LINK-02"))
                .andExpect(jsonPath("$.items[1].status").value("BLOQUEADA_MANUAL"))
                .andExpect(jsonPath("$.items[1].stores[0].id").value(linked.toString()))
                .andExpect(jsonPath("$.items[1].stores[1].id").value(stores.get(1).toString()))
                .andExpect(jsonPath("$.items[2].reference").value("LINK-03"))
                .andExpect(jsonPath("$.items[2].status").value("CADUCADA"));
        mvc.perform(get(path).header("Authorization", token).param("storeId", linked.toString())
                        .param("companyId", empty.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        var exported = workbook(get(DIRECTORIES + "licenses").param("storeId", linked.toString())
                .param("companyId", company.toString()).param("sortBy", "reference").param("language", "en"), token, "licenses");
        assertThat(exported.get("xl/worksheets/sheet1.xml")).contains("A1:M4", ">LINK-01<", ">LINK-02<", ">LINK-03<",
                ">Valid<", ">Blocked<", ">Expired<").doesNotContain("LINK-04");
        assertThat(exported.get("xl/worksheets/sheet2.xml")).contains(">Store<", ">001 · Linked store<")
                .doesNotContain(linked.toString());
        var mismatched = workbook(get(DIRECTORIES + "licenses").param("storeId", linked.toString())
                .param("companyId", empty.toString()), token, "licenses");
        assertThat(mismatched.get("xl/worksheets/sheet1.xml")).doesNotContain("<row r=\"2\"");
        var missing = workbook(get(DIRECTORIES + "licenses").param("storeId", UUID.randomUUID().toString())
                .param("language", "en"), token, "licenses");
        assertThat(missing.get("xl/worksheets/sheet1.xml")).doesNotContain("<row r=\"2\"");
        assertThat(missing.get("xl/worksheets/sheet2.xml")).contains(">Store not found<");
    }

    private UUID company(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into saas_company(id,name,tax_id,taxpayer_type,created_at)
                values (?,?,?,'SOCIEDAD',now())
                """, id, name, id.toString().substring(0, 20));
        return id;
    }

    private void enableEnterprisePlan(UUID company) {
        jdbc.update("""
                insert into saas_company_operations(company_id,plan_name,billing_status,support_status,updated_at)
                values (?,'ENTERPRISE','PENDIENTE','NORMAL',now())
                """, company);
    }

    private static MockHttpServletRequestBuilder companiesRequest(String body) {
        return post(DIRECTORIES + "companies").contentType("application/json").content(body);
    }

    private Map<String, String> workbook(MockHttpServletRequestBuilder request, String token, String directory)
            throws Exception {
        var response = mvc.perform(request.header("Authorization", token)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"espos-" + directory + ".xlsx\""))
                .andReturn().getResponse();
        var parts = new HashMap<String, String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                parts.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
        }
        return parts;
    }

    @BeforeEach
    void rememberTemporaryFiles() throws IOException {
        before = temporaryFiles();
    }

    @AfterEach
    void leavesNoTemporaryExports() throws IOException {
        assertThat(temporaryFiles()).isEqualTo(before);
    }

    @Test
    void requiresStaffAndExportsEveryPageWithTheOriginalFilters() throws Exception {
        String adminToken = adminToken("VIEW_ADMIN_DATA");
        String tenantToken = "Bearer " + sessions.issue("tenant", "tenant-reports").token();
        var request = dates("articles").param("companyId", COMPANY.toString()).param("storeId", STORE.toString())
                .param("province", "38").param("search", "Café").param("sort", "CODE").param("direction", "ASC")
                .param("page", "20").param("size", "1").param("language", "en");
        mvc.perform(request).andExpect(status().isUnauthorized());
        mvc.perform(request.header("Authorization", tenantToken)).andExpect(status().isUnauthorized());
        for (int page = 0; page < 3; page++) {
            int start = page * ANALYTICS_PAGE_SIZE;
            var rows = IntStream.range(start, Math.min(start + ANALYTICS_PAGE_SIZE, 202))
                    .mapToObj(ReportExportPostgreSqlTest::article).toList();
            when(articles.report(PERIOD, null, COMPANY, STORE, "38", "Café", ArticleReport.Sort.CODE,
                    ArticleReport.Direction.ASC, page, ANALYTICS_PAGE_SIZE))
                    .thenReturn(new ArticleReport(NOW, PERIOD, null, page, ANALYTICS_PAGE_SIZE, 202, COVERAGE, rows));
        }
        var result = mvc.perform(dates("articles").param("companyId", COMPANY.toString())
                        .param("storeId", STORE.toString()).param("province", "38").param("search", "Café")
                        .param("sort", "CODE").param("direction", "ASC").param("page", "20").param("size", "1")
                        .param("language", "en").header("Authorization", adminToken))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"espos-articles.csv\""))
                .andReturn().getResponse();
        byte[] bytes = result.getContentAsByteArray();
        assertThat(Long.parseLong(result.getHeader("Content-Length"))).isEqualTo(bytes.length);
        String csv = new String(bytes, StandardCharsets.UTF_8);
        assertThat(csv).contains("\"# Rows\";\"202\"", "\"# Period · From\";\"2025-01-01\"",
                "\"# Generated (UTC)\";\"2025-02-01T00:00:00Z\"", "\"# Sort direction\";\"Ascending\"",
                "\"# Province\";\"38\"", "\"# Search\";\"Café\"", "\"Café 0\"", "\"Café 100\"", "\"Café 201\"",
                "\"Current · Average quantity per sale\"");
        assertThat(csv.substring(csv.indexOf("\r\n\r\n") + 4).lines().count()).isEqualTo(203);
        verify(articles).report(PERIOD, null, COMPANY, STORE, "38", "Café", ArticleReport.Sort.CODE,
                ArticleReport.Direction.ASC, 2, ANALYTICS_PAGE_SIZE);
        String readable = csv(dates("articles").param("layout", "readable").param("companyId", COMPANY.toString())
                .param("storeId", STORE.toString()).param("province", "38").param("search", "Café")
                .param("sort", "CODE").param("direction", "ASC").param("page", "20").param("size", "1"));
        assertThat(readable).startsWith("\ufeff\"Empresa\";\"Tienda\";\"Código\";\"Producto\";\"Moneda\";\"Cantidad neta\";\"Importe neto\"")
                .contains("\"Café 201\";\"EUR\";\"1\";\"2.50\"", "\"# Búsqueda\";\"Café\"", COMPANY.toString(),
                        STORE.toString()).doesNotContain("ID producto", "Comparación ·");
        assertThat(readable.substring(0, readable.indexOf("\r\n\r\n")).lines().count()).isEqualTo(203);
    }

    @Test
    void rejectsOverflowAndInvalidDatesBeforeSendingAnAttachment() throws Exception {
        when(articles.report(PERIOD, null, null, null, null, null, ArticleReport.Sort.AMOUNT, null,
                0, ANALYTICS_PAGE_SIZE)).thenReturn(new ArticleReport(NOW, PERIOD, null, 0,
                        ANALYTICS_PAGE_SIZE, ReportExportService.LIMIT + 1, COVERAGE, List.of()));
        String token = adminToken("VIEW_REPORTS");
        mvc.perform(dates("articles").header("Authorization", token))
                .andExpect(status().isUnprocessableContent()).andExpect(header().doesNotExist("Content-Disposition"));
        mvc.perform(dates("articles").param("layout", "readable").header("Authorization", token))
                .andExpect(status().isUnprocessableContent()).andExpect(header().doesNotExist("Content-Disposition"));
        mvc.perform(dates("statistics").param("layout", "readable").param("view", "missing").header("Authorization", token))
                .andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Content-Disposition"));
        mvc.perform(dates("articles").param("layout", "missing").header("Authorization", token))
                .andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Content-Disposition"));
        mvc.perform(get(PATH + "articles").param("from", "2025-02-30").param("to", "2025-03-03")
                        .header("Authorization", token))
                .andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Content-Disposition"));
    }

    @Test
    void emptyDocumentsRetainLocalizedHeadersAndForwardCurrencyAndProductFallback() throws Exception {
        when(documents.report(PERIOD, COMPANY, STORE, "38", null, "0009", "EUR", DocumentReport.Sort.DATE,
                null, 0, ANALYTICS_PAGE_SIZE))
                .thenReturn(new DocumentReport(NOW, PERIOD, 0, ANALYTICS_PAGE_SIZE, 0, COVERAGE, List.of()));
        String csv = csv(dates("documents").param("companyId", COMPANY.toString()).param("storeId", STORE.toString())
                .param("province", "38").param("currency", "EUR").param("productCode", "0009").param("language", "zh"));
        assertThat(csv).contains("\"# 行数\";\"0\"", "\"# 币种\";\"EUR\"", "\"# 编码\";\"'0009\"",
                "\r\n\r\n\"单据ID\";\"类型\";\"编号\";\"状态\";\"业务日期\"");
        assertThat(csv.substring(csv.indexOf("\r\n\r\n") + 4).lines().count()).isEqualTo(1);
    }

    @Test
    void statisticsIncludeCurrenciesOnlyPresentOnLaterPages() throws Exception {
        var eur = currency("EUR", "15.00");
        var usd = currency("USD", "9007199254740993.123");
        var totals = new ReportStatistics.PeriodPair(metrics(List.of(eur, usd)), null, List.of());
        var firstRows = IntStream.range(0, ANALYTICS_PAGE_SIZE)
                .mapToObj(row -> new ReportStatistics.Row("first" + row, "First " + row, null,
                        metrics(List.of(eur)), null, List.of())).toList();
        when(statistics.report(PERIOD, null, ReportStatistics.GroupBy.STORE, null, null, null, 0, ANALYTICS_PAGE_SIZE))
                .thenReturn(stats(totals, firstRows, 0));
        when(statistics.report(PERIOD, null, ReportStatistics.GroupBy.STORE, null, null, null, 1, ANALYTICS_PAGE_SIZE))
                .thenReturn(stats(totals, List.of(new ReportStatistics.Row("last", "Last page", null,
                        metrics(List.of(usd)), null, List.of())), 1));

        String csv = csv(dates("statistics").param("groupBy", "STORE").param("language", "en"));
        String[] data = csv.substring(csv.indexOf("\r\n\r\n") + 4).split("\r\n");
        assertThat(data[0]).contains("Current · Amounts by currency · USD · Amount");
        assertThat(data[1]).doesNotContain("9007199254740993.123");
        assertThat(data[101]).contains("Last page", "'9007199254740993.123");

        String readable = csv(dates("statistics").param("layout", "readable").param("view", "sales")
                .param("groupBy", "STORE").param("language", "en"));
        String[] sections = readable.split("\r\n\r\n", 2);
        assertThat(sections[0]).startsWith("\ufeff\"Store\";\"Net amount (EUR)\";\"Net amount (USD)\";\"Eligible documents\"")
                .contains("\"Last page\";\"\";\"'9007199254740993.123\";\"1\"");
        assertThat(sections[0].lines().count()).isEqualTo(102);
        assertThat(sections[1]).contains("\"# Total · Net amount (EUR)\";\"15.00\"",
                "\"# Total · Net amount (USD)\";\"'9007199254740993.123\"", "\"# Period · From\";\"2025-01-01\"",
                "\"# Generated (UTC)\";\"2025-02-01T00:00:00Z\"").doesNotContain("Sync", "Average", "Compared");
        String sync = csv(dates("statistics").param("layout", "readable").param("view", "sync")
                .param("groupBy", "STORE").param("language", "zh"));
        assertThat(sync).startsWith("\ufeff\"门店\";\"接收事件\";\"错误\";\"变动接收记录\"")
                .doesNotContain("EUR", "USD");
        String averages = csv(dates("statistics").param("layout", "readable").param("view", "averages")
                .param("groupBy", "STORE"));
        assertThat(averages).startsWith("\ufeff\"Tienda\";\"Importe por documento (EUR)\"")
                .contains("\"Importe por día (EUR)\"", "\"Importe por tienda con ventas (USD)\"")
                .doesNotContain("Importe neto", "Eventos recibidos");
    }

    @Test
    void emptyReadableSalesRetainAmountColumnWithoutInventingCurrencyOrZero() throws Exception {
        var totals = new ReportStatistics.PeriodPair(metrics(List.of()), null, List.of());
        when(statistics.report(PERIOD, null, ReportStatistics.GroupBy.DAY, null, null, null, 0, ANALYTICS_PAGE_SIZE))
                .thenReturn(new ReportStatistics(NOW, PERIOD, null, ReportStatistics.GroupBy.DAY, 0, ANALYTICS_PAGE_SIZE,
                        0, COVERAGE, totals, List.of(), List.of(), List.of()));
        String csv = csv(dates("statistics").param("layout", "readable"));
        assertThat(csv).startsWith("\ufeff\"Día\";\"Importe neto\";\"Documentos computables\"\r\n\r\n")
                .doesNotContain("EUR", "USD", "# Total · Importe neto");
    }

    @Test
    void distinguishesUncoveredProvincesFromEmptyCompleteSnapshots() throws Exception {
        when(inventory.provinces(COMPANY, STORE, "38")).thenReturn(List.of(
                new InventoryReport.Province("38", new InventoryReport.Totals(1, 0, 0, 0, 0, 0, 0, 0, 0)),
                new InventoryReport.Province("39", new InventoryReport.Totals(1, 1, 0, 0, 0, 0, 0, 0, 0))));
        String csv = csv(get(PATH+"inventory").param("kind", "provinces").param("companyId", COMPANY.toString())
                .param("storeId", STORE.toString()).param("province", "38"));
        assertThat(csv).contains("\"38\";\"1\";\"0\";\"\";\"\";\"\";\"\";\"\";\"\";\"\"",
                "\"39\";\"1\";\"1\";\"0\";\"0\";\"0\";\"0\";\"0\";\"0\";\"0\"");
        String readable = csv(get(PATH+"inventory").param("layout", "readable").param("kind", "provinces")
                .param("companyId", COMPANY.toString()).param("storeId", STORE.toString()).param("province", "38"));
        assertThat(readable).startsWith("\ufeff\"Provincia\";\"Tiendas\";\"Tiendas con captura\"")
                .contains("\"38\";\"1\";\"0\";\"\";\"\";\"\";\"\"\r\n", "\"39\";\"1\";\"1\";\"0\";\"0\";\"0\";\"0\"\r\n");
    }

    @Test
    void removesPartialFileWhenALaterPageFailsAndAcceptsARetry() throws Exception {
        var sort = InventoryReport.OverviewSort.COMPANY;
        var direction = InventoryReport.Direction.ASC;
        var totals = new InventoryReport.Totals(201, 0, 0, 0, 0, 0, 0, 0, 0);
        var row = new InventoryReport.Store(COMPANY, "Empresa", STORE, "Tienda", null);
        when(inventory.overview(null, null, null, sort, direction, 0, INVENTORY_PAGE_SIZE))
                .thenReturn(new InventoryReport.Overview(0, INVENTORY_PAGE_SIZE, 201, totals,
                        java.util.Collections.nCopies(INVENTORY_PAGE_SIZE, row)));
        when(inventory.overview(null, null, null, sort, direction, 1, INVENTORY_PAGE_SIZE))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE))
                .thenReturn(new InventoryReport.Overview(1, INVENTORY_PAGE_SIZE, 201, totals, List.of(row)));
        String token = adminToken("VIEW_REPORTS");
        mvc.perform(get(PATH + "inventory").header("Authorization", token))
                .andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist("Content-Disposition"));
        assertThat(temporaryFiles()).isEqualTo(before);
        mvc.perform(get(PATH + "inventory").header("Authorization", token)).andExpect(status().isOk());
    }

    private static ArticleReport.Row article(int index) {
        var metrics = new ArticleReport.Metrics("1", "0", "1", "2.50", "0.00", "2.50", 1, "1.00", "0.01");
        return new ArticleReport.Row(index + 1, COMPANY, "Empresa", STORE, "Tienda", null, "0" + index,
                "Café " + index, "EUR", metrics, null, null, null, null);
    }

    private static ReportStatistics.CurrencyMetrics currency(String code, String amount) {
        return new ReportStatistics.CurrencyMetrics(code, 1L, amount, amount, amount, 1L, amount);
    }

    private static ReportStatistics.PeriodMetrics metrics(List<ReportStatistics.CurrencyMetrics> currency) {
        return new ReportStatistics.PeriodMetrics(1, 1L, 0, 0, 0, 0, currency, 1, 1, 1L, 0L);
    }

    private static ReportStatistics stats(ReportStatistics.PeriodPair totals, List<ReportStatistics.Row> rows, int page) {
        return new ReportStatistics(NOW, PERIOD, null, ReportStatistics.GroupBy.STORE, page, ANALYTICS_PAGE_SIZE, 101,
                COVERAGE, totals, rows, List.of(), List.of());
    }

    private static MockHttpServletRequestBuilder dates(String resource) {
        return get(PATH + resource).param("from", PERIOD.from().toString()).param("to", PERIOD.to().toString());
    }

    private String csv(MockHttpServletRequestBuilder request) throws Exception {
        return new String(mvc.perform(request.header("Authorization", adminToken("VIEW_REPORTS"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private String adminToken(String permission) {
        String username = "export-" + UUID.randomUUID();
        var user = admins.saveAndFlush(new SaasAdminUser(UUID.randomUUID(), username, "unused-test-hash", true, NOW));
        UUID role = UUID.randomUUID();
        jdbc.update("insert into saas_admin_role(id,name,created_at) values (?,?,now())", role, username);
        jdbc.update("insert into saas_admin_user_role(user_id,role_id) values (?,?)", user.getId(), role);
        jdbc.update("insert into saas_admin_role_permission(role_id,permission_code) values (?,?)", role, permission);
        return "Bearer " + sessions.issue("admin", username).token();
    }

    private static Set<Path> temporaryFiles() throws IOException {
        try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(path -> path.getFileName().toString().startsWith(ReportExportService.TEMP_PREFIX))
                    .collect(Collectors.toSet());
        }
    }
}
