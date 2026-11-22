package com.tpverp.saas.analytics;

import static com.tpverp.saas.analytics.ReportStatistics.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tpverp.saas.admin.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"spring.flyway.default-schema=report_statistics_test",
        "spring.datasource.hikari.schema=report_statistics_test",
        "spring.jpa.properties.hibernate.default_schema=report_statistics_test"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class ReportStatisticsPostgreSqlTest extends CommercialProjectionTestSupport {
    private static final String PATH = "/api/v1/admin/reports/statistics";
    private static final String DATES = "?from=2025-01-01&to=2025-01-03";
    private static final Period JAN = period("2025-01-01", "2025-01-03");
    @Autowired ReportStatisticsService reports;
    @Autowired ArticleReportService articles;
    @Autowired DocumentReportService documents;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired SaasSessionTokenStore sessions;
    @Autowired SaasAdminUserRepository admins;

    @Test void bothEndpointsRequireInternalStaffAndReturnUncachedMinimalOptions() throws Exception {
        var site = site("Scope", "B91100010");
        String reportsToken = adminToken("stats-reports", "VIEW_REPORTS");
        String adminToken = adminToken("stats-read", "VIEW_ADMIN_DATA");
        String tenant = "Bearer " + sessions.issue("tenant", "stats-reports").token();
        for (String path : List.of(PATH + DATES, PATH + "/options", "/api/v1/admin/reports/articles" + DATES,
                "/api/v1/admin/reports/documents" + DATES)) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", tenant)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", adminToken)).andExpect(status().isOk());
            mvc.perform(get(path).header("Authorization", reportsToken)).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(get(PATH + "/options").header("Authorization", reportsToken)).andExpect(content().json("""
                {"companies":[{"id":"%s","name":"Scope"}],"stores":[{"id":"%s","companyId":"%s","name":"Store Scope"}]}
                """.formatted(site.company().getId(), site.store().getId(), site.company().getId()),
                org.springframework.test.json.JsonCompareMode.STRICT));
        mvc.perform(get(PATH + DATES).header("Authorization", reportsToken))
                .andExpect(jsonPath("$.totalGroups").value(3)).andExpect(jsonPath("$.totals.current.documents").value(0))
                .andExpect(jsonPath("$.comparisonPeriod").isEmpty()).andExpect(jsonPath("$.totals.comparison").isEmpty())
                .andExpect(jsonPath("$.syncByStatus.length()").value(4));
    }

    @Test void rejectsInvalidDatesMixedScopesAndPagination() throws Exception {
        var a = site("A", "B91100010");
        var b = site("B", "B91100020");
        String token = adminToken("stats-invalid", "VIEW_REPORTS");
        for (String query : List.of("", "?from=2025-01-01", "?from=no&to=2025-01-01",
                "?from=2025-01-01&to=2024-12-31", "?from=2024-01-01&to=2025-01-01",
                "?from=0001-01-01&to=0001-01-02&comparison=PREVIOUS_PERIOD",
                "?from=0000-01-01&to=0000-01-02", DATES + "&comparison=FOO", DATES + "&comparison=CUSTOM",
                DATES + "&comparison=CUSTOM&comparisonFrom=2020-01-01&comparisonTo=2021-01-01",
                DATES + "&comparisonFrom=2024-01-01", DATES + "&groupBy=region", DATES + "&page=-1",
                DATES + "&size=0", DATES + "&size=101", DATES + "&storeId=" + UUID.randomUUID(),
                DATES + "&companyId=" + UUID.randomUUID(), DATES + "&companyId=" + a.company().getId()
                        + "&storeId=" + b.store().getId())) {
            mvc.perform(get(PATH + query).header("Authorization", token)).andExpect(status().isBadRequest());
        }
    }

    @Test void calendarYearAndCustomComparisonAlignDaysByOrdinalIncludingEmptySides() {
        var leap = period("2025-01-01", "2026-01-01");
        var year = leap.comparison(Comparison.PREVIOUS_YEAR, null, null);
        var page = reports.report(leap, year, GroupBy.DAY, null, null, 366, 1);
        assertThat(page.totalGroups()).isEqualTo(367);
        assertThat(page.comparisonPeriod().days()).isEqualTo(367);
        assertThat(page.rows()).singleElement().satisfies(row -> {
            assertThat(row.key()).isEqualTo("366");
            assertThat(row.label()).isNull();
            assertThat(row.comparisonLabel()).isEqualTo("2025-01-01");
            assertThat(row.current()).isNull();
            assertThat(row.comparison().documents()).isZero();
        });
        var custom = JAN.comparison(Comparison.CUSTOM, LocalDate.parse("2024-02-28"), LocalDate.parse("2024-02-29"));
        var lastDay = reports.report(JAN, custom, GroupBy.DAY, null, null, 0, 25).rows().getLast();
        assertThat(lastDay.current().documents()).isZero();
        assertThat(lastDay.comparisonLabel()).isNull();
        assertThat(lastDay.comparison()).isNull();
        var twoMonths = JAN.comparison(Comparison.CUSTOM,LocalDate.parse("2024-01-01"),LocalDate.parse("2024-02-15"));
        var monthly = reports.report(JAN,twoMonths,GroupBy.MONTH,null,null,0,25);
        assertThat(monthly.rows().getLast()).satisfies(row -> {
            assertThat(row.current()).isNull();
            assertThat(row.comparisonLabel()).isEqualTo("2024-02-01");
            assertThat(row.comparison().documents()).isZero();
            assertThat(row.deltas()).isEmpty();
        });
        assertThat(monthly.totals().current().documents()).isZero();
        assertThat(monthly.totals().comparison().documents()).isZero();
        assertThat(JAN.comparison(Comparison.PREVIOUS_PERIOD, null, null)).isEqualTo(period("2024-12-29", "2024-12-31"));
    }

    @Test void usesBusinessDatesLatestHeaderUnboundedConversionsAndSeparateSignedCurrencyAverages() {
        Site site = site("A", "B91100010");
        UUID ticketId = UUID.randomUUID();
        receive(site, ticketId, document("TICKET","PAGADO","EUR","80.00","A"));
        var converted = document("FACTURA_VENTA","PAGADO","EUR","80.00","A");
        converted.put("fecha", "2025-06-10");
        converted.put("relaciones", List.of(Map.of("tipo","FACTURA_DE","origenId",ticketId.toString())));
        receive(site, UUID.randomUUID(), converted);
        UUID currentId = UUID.randomUUID();
        var current = document("FACTURA_VENTA","PAGADO","EUR","12.00","B");
        receive(site, currentId, current);
        current.put("sourceRevision",2);
        current.put("total","13.30");
        receive(site, currentId, current);
        var negative = document("RECTIFICATIVA_VENTA","PAGADO","EUR","-1.35","B");
        negative.put("relaciones", List.of(Map.of("tipo","RECTIFICA","origenId",currentId.toString())));
        receive(site, UUID.randomUUID(), negative);
        var usd = document("TICKET","PAGADO","USD","33.10","C");
        usd.remove("lineas");
        receive(site, UUID.randomUUID(), usd);
        receive(site, UUID.randomUUID(), document("TICKET","ANULADO","EUR","50.00","D"));
        var report = reports.report(JAN, JAN.comparison(Comparison.PREVIOUS_PERIOD,null,null),
                GroupBy.DAY, null, null, 0, 25);
        assertThat(report.coverage()).isEqualTo(new Coverage(0,1,0L));
        var total = report.totals().current();
        assertThat(total.documents()).isEqualTo(5);
        assertThat(total.eligibleDocuments()).isEqualTo(3);
        assertThat(total.cancelledDocuments()).isOne();
        assertThat(total.syncEvents()).isZero();
        assertThat(total.currencies()).containsExactly(new CurrencyMetrics("EUR",2L,"11.95","5.98","3.98",1L,"11.95"),
                new CurrencyMetrics("USD",1L,"33.10","33.10","11.03",1L,"33.10"));
        assertThat(total.saleDocuments()).isEqualTo(2);
        assertThat(total.returnDocuments()).isOne();
        assertThat(report.totals().deltas()).containsExactly(new Delta("EUR","11.95",null), new Delta("USD","33.10",null));
        assertThat(report.rows().getFirst().current().currencies().getFirst().amountPerDay()).isEqualTo("11.95");
        assertThat(report.totals().comparison().documents()).isZero();
        assertThat(report.totals().comparison().currencies()).isEmpty();
    }

    @Test void uncertainOutsideBothPeriodsMasksBothSidesButDoesNotContaminateOtherStores() {
        Site a = site("A", "B91100010"), b = site("B", "B91100020");
        receive(a, UUID.randomUUID(), document("TICKET","PAGADO","EUR","10.00","A"));
        receive(b, UUID.randomUUID(), document("TICKET","PAGADO","EUR","5.00","B"));
        var old = document("TICKET","PAGADO","EUR","8.00","X");
        old.remove("relaciones");
        old.put("fecha","2020-01-01");
        receive(b, UUID.randomUUID(), old);
        var result = reports.report(JAN,JAN,GroupBy.STORE,null,null,0,25);
        assertThat(result.coverage()).isEqualTo(new Coverage(1,0,0L));
        assertThat(result.totals().current().documents()).isEqualTo(2);
        for (var metrics : List.of(result.totals().current(),result.totals().comparison(),
                result.rows().getFirst().current(),result.rows().getLast().comparison())) {
            assertThat(metrics.eligibleDocuments()).isNull();
            assertThat(metrics.currencies()).containsExactly(new CurrencyMetrics("EUR",null,null,null,null,null,null));
        }
        var clean = reports.report(JAN,null,GroupBy.STORE,a.company().getId(),a.store().getId(),0,25);
        assertThat(clean.coverage().unknownRelationships()).isZero();
        assertThat(clean.totals().current().currencies().getFirst().amount()).isEqualTo("10.00");
    }

    @Test void datesForReceiptsUseUtcEvenWhenDatabaseZoneDiffersAndCountsIncludeMoreThan200() {
        var site = site("Receipts", "B91100010");
        jdbc.execute("set local time zone 'Pacific/Honolulu'");
        receipt(site, "2024-12-31T23:59:59.999999Z","STOCK_MOVEMENT","IGNORED",1);
        receipt(site, "2025-01-01T00:00:00Z","STOCK_MOVEMENT","RECEIVED",201);
        receipt(site, "2025-01-03T23:59:59.999999Z","DOCUMENTO","ERROR",2);
        receipt(site, "2025-01-04T00:00:00Z","DOCUMENTO","PROJECTED",1);
        var result = reports.report(JAN,JAN.comparison(Comparison.PREVIOUS_PERIOD,null,null),GroupBy.DAY,null,null,0,25);
        assertThat(result.totals().current().syncEvents()).isEqualTo(203);
        assertThat(result.totals().current().stockMovementEvents()).isEqualTo(201);
        assertThat(result.totals().current().syncErrors()).isEqualTo(2);
        assertThat(result.totals().comparison().syncEvents()).isOne();
        assertThat(result.syncByType()).containsExactly(new EventCount("DOCUMENTO",2,0L),new EventCount("STOCK_MOVEMENT",201,1L));
        assertThat(result.syncByStatus()).containsExactly(new EventCount("RECEIVED",201,0L),
                new EventCount("PROJECTED",0,0L),new EventCount("IGNORED",0,1L),new EventCount("ERROR",2,0L));
        assertThat(result.rows()).extracting(r -> r.current().syncEvents()).containsExactly(201L,0L,2L);
    }

    @Test void provinceUsesCurrentStorePostalAddressAndDimensionPagesUseBothPeriods() {
        var a = site("Alpha", "B91100010");
        var b = site("Beta", "B91100020");
        var c = site("Gamma", "B91100030");
        jdbc.update("update saas_store set store_address='{\"pais\":\"ES\",\"codigoPostal\":\"28001\"}' where id=?",a.store().getId());
        jdbc.update("update saas_store set store_address='{\"pais\":\"ES\",\"codigoPostal\":\"35001\"}' where id=?",a.store().getId());
        jdbc.update("update saas_store set store_address='{\"pais\":\"FR\",\"codigoPostal\":\"35001\"}' where id=?",b.store().getId());
        receipt(a,"2025-01-01T12:00:00Z","A","RECEIVED",1);
        receipt(b,"2024-12-31T12:00:00Z","B","PROJECTED",1);
        receipt(c,"2024-12-31T12:00:00Z","C","RECEIVED",1);
        var prior = JAN.comparison(Comparison.PREVIOUS_PERIOD,null,null);
        var province = reports.report(JAN,prior,GroupBy.PROVINCE,null,null,0,25);
        assertThat(province.rows()).extracting(Row::key).containsExactly("35","UNKNOWN");
        assertThat(province.rows().getLast().comparison().syncEvents()).isEqualTo(2);
        var page = reports.report(JAN,prior,GroupBy.COMPANY,null,null,1,1);
        assertThat(page.totalGroups()).isEqualTo(3);
        assertThat(page.rows()).singleElement().satisfies(row -> {
            assertThat(row.label()).isEqualTo("Beta");
            assertThat(row.current().syncEvents()).isZero();
            assertThat(row.comparison().syncEvents()).isOne();
        });
        assertThat(page.totals().current().syncEvents()).isOne();
        assertThat(page.totals().comparison().syncEvents()).isEqualTo(2);
        var beyond = reports.report(JAN,prior,GroupBy.STORE,null,null,100,3);
        assertThat(beyond.rows()).isEmpty();
        assertThat(beyond.totalGroups()).isEqualTo(3);
        assertThat(beyond.totals()).isEqualTo(page.totals());
    }

    @Test void completeSelectorsAndSqlPagesIncludeLastStoreBeyond200() {
        var site = site("Initial", "B91100010");
        jdbc.update("""
                insert into saas_company(id,name,tax_id,taxpayer_type,tax_regime,created_at)
                select gen_random_uuid(),'Company '||n,'TEST-'||n,'SOCIEDAD','IVA',now() from generate_series(1,201) n
                """);
        jdbc.update("""
                insert into saas_store(id,company_id,code,name,created_at,time_zone_id,tax_regime,commercial_profile)
                select gen_random_uuid(),id,'001',name,now(),'UTC','IVA','MINORISTA' from saas_company where id<>?
                """,site.company().getId());
        var options = reports.options();
        assertThat(options.companies()).hasSize(202);
        assertThat(options.stores()).hasSize(202);
        jdbc.update("""
                insert into saas_sync_event(event_id,company_id,store_id,entity_type,entity_id,operation,payload,
                     received_at,schema_version,payload_hash,projection_status)
                select gen_random_uuid(),company_id,id,'A',gen_random_uuid(),'CREAR','{}','2025-01-01T00:00:00Z',1,
                       repeat('0',64),'RECEIVED' from saas_store
                """);
        var last = reports.report(JAN,null,GroupBy.STORE,null,null,2,100);
        assertThat(last.rows()).hasSize(2);
        assertThat(last.totalGroups()).isEqualTo(202);
        assertThat(last.totals().current().syncEvents()).isEqualTo(202);
    }

    @Test void calendarBucketsRespectPartialWeeksMonthsProvinceAndCoverageWithoutSales() {
        var a = site("Calendar","B91100011");
        var b = site("Quiet","B91100022");
        jdbc.update("update saas_store set store_address='{\"pais\":\"ES\",\"codigoPostal\":\"28001\"}' where id=?",a.store().getId());
        jdbc.update("update saas_store set store_address='{\"pais\":\"ES\",\"codigoPostal\":\"35001\"}' where id=?",b.store().getId());
        var current = period("2025-01-30","2025-02-05");
        var prior = period("2025-01-01","2025-01-07");
        var doc = document("TICKET","PAGADO","EUR","12","X");
        doc.put("fecha","2025-02-02");
        receive(a, UUID.randomUUID(), doc);
        doc.put("fecha","2025-01-02");
        doc.put("total","8");
        receive(a, UUID.randomUUID(), doc);
        var weeks = reports.report(current,prior,GroupBy.WEEK,null,null,"28",0,5);
        assertThat(weeks.rows()).extracting(Row::label).containsExactly("2025-01-30","2025-02-03");
        assertThat(weeks.rows().getFirst().current().currencies().getFirst().amountPerDay()).isEqualTo("3.00");
        assertThat(weeks.totals().deltas()).containsExactly(new Delta("EUR","4.00","50.00"));
        var months = reports.report(current,prior,GroupBy.MONTH,null,null,"28",0,5);
        assertThat(months.rows()).extracting(Row::label).containsExactly("2025-01-30","2025-02-01");
        assertThat(months.rows().getLast().comparisonLabel()).isNull();
        assertThat(months.rows().getLast().comparison()).isNull();
        assertThat(months.rows().getLast().deltas()).isEmpty();
        assertThat(months.rows().getLast().current().currencies().getFirst().amountPerDay()).isEqualTo("2.40");
        var provinces = reports.report(current,null,GroupBy.PROVINCE,null,null,0,20);
        assertThat(provinces.rows()).extracting(Row::label).containsExactly("28","35");
        assertThat(provinces.rows().getLast().current()).satisfies(m -> {
            assertThat(m.documents()).isZero();
            assertThat(m.stores()).isOne();
            assertThat(m.licenses()).isOne();
        });
    }

    @Test void articlesUseSeparateIdentitiesSignedDecimalsAndUnboundedConversionGuard() {
        var a = site("Articles","B91100012");
        var b = site("Another","B91100023");
        var product = UUID.randomUUID();
        var sale = productDocument("TICKET","EUR","2025-01-02","2.50","25.00",product,"001","BASE");
        UUID saleId = UUID.randomUUID();
        receive(a,saleId,sale);
        receive(a,UUID.randomUUID(),productDocument("TICKET","EUR","2024-12-31","1","10.00",product,"001","BASE"));
        receive(a,UUID.randomUUID(),productDocument("RECTIFICATIVA_VENTA","EUR","2025-01-02","-0.5","-5.00",product,"001","BASE"));
        receive(a,UUID.randomUUID(),productDocument("RECTIFICATIVA_VENTA","EUR","2025-01-02","1","-2.00",product,"001","DIFERENCIA"));
        receive(b,UUID.randomUUID(),productDocument("TICKET","USD","2025-01-02","2","80.00",product,"001","BASE"));
        receive(a,UUID.randomUUID(),productDocument("TICKET","EUR","2025-01-02","1","0.25",UUID.randomUUID(),"001","BASE"));
        UUID convertedId = UUID.randomUUID();
        receive(a,convertedId,productDocument("TICKET","EUR","2025-01-01","1","100.00",product,"001","BASE"));
        var invoice = productDocument("FACTURA_VENTA","EUR","2025-03-01","1","100.00",product,"001","BASE");
        invoice.put("relaciones", List.of(Map.of("tipo","FACTURA_DE","origenId",convertedId.toString())));
        receive(a,UUID.randomUUID(),invoice);
        var prior = JAN.comparison(Comparison.PREVIOUS_PERIOD,null,null);
        var report = articles.report(JAN,prior,null,null,null,"001",ArticleReport.Sort.AMOUNT,0,1);
        assertThat(report.totalRows()).isEqualTo(3);
        assertThat(report.rows()).singleElement().satisfies(row -> {
            assertThat(row.currency()).isEqualTo("EUR");
            assertThat(row.companyId()).isEqualTo(a.company().getId());
            assertThat(row.productId()).isEqualTo(product);
            assertThat(row.current().quantitySold()).isEqualTo("2.50");
            assertThat(row.current().quantityReturned()).isEqualTo("0.5");
            assertThat(row.current().netAmount()).isEqualTo("18.00");
            assertThat(row.current().returnsAmount()).isEqualTo("7.00");
            assertThat(row.current().saleDocuments()).isOne();
            assertThat(row.current().averageQuantityPerSale()).isEqualTo("2.500");
            assertThat(row.current().amountSharePercent()).isEqualTo("98.63");
            assertThat(row.comparison().amountSharePercent()).isEqualTo("100.00");
            assertThat(row.deltaAmount()).isEqualTo("8.00");
            assertThat(row.deltaPercent()).isEqualTo("80.00");
            assertThat(row.deltaQuantity()).isEqualTo("1.00");
        });
        var asc = articles.report(JAN,prior,null,null,null,null,ArticleReport.Sort.AMOUNT,ArticleReport.Direction.ASC,0,1);
        assertThat(asc.rows().getFirst().current().netAmount()).isEqualTo("0.25");
        var beyond = articles.report(JAN,prior,null,null,null,null,ArticleReport.Sort.QUANTITY,5,1);
        assertThat(beyond.rows()).isEmpty();
        assertThat(beyond.totalRows()).isEqualTo(3);
        assertThat(articles.report(JAN,prior,null,null,null,"%",ArticleReport.Sort.CODE,0,10).rows()).isEmpty();
    }

    @Test void articleIncompleteProjectionsWithholdRanksAndComparisonsIndependentlyOfSearch() {
        var a = site("Partial","B91100013");
        var good = site("Complete","B91100024");
        receive(good,UUID.randomUUID(),document("TICKET","PAGADO","EUR","3","G"));
        var old = document("TICKET","PAGADO","EUR","4","X");
        old.remove("relaciones");
        old.put("fecha","2020-01-01");
        receive(a,UUID.randomUUID(),old);
        var missing = document("TICKET","PAGADO","EUR","3","M");
        missing.remove("lineas");
        receive(a,UUID.randomUUID(),missing);
        var all = articles.report(JAN,null,null,null,null,"G",ArticleReport.Sort.AMOUNT,0,10);
        assertThat(all.rows()).isEmpty();
        assertThat(all.coverage()).isEqualTo(new Coverage(1,1,null));
        assertThat(articles.report(JAN,null,good.company().getId(),null,null,null,ArticleReport.Sort.CODE,0,10)
                .rows()).hasSize(1);
    }

    @Test void lineParticipationUsesScopeCurrencyBeforeSearchAndCountsDistinctSaleDocuments() {
        var a = site("Portion","B91100014");
        var id = UUID.randomUUID();
        var sale = productDocument("TICKET","EUR","2025-01-02","2.5","12.50",id,"A","BASE");
        sale.put("subtotal","15.00");
        sale.put("total","15.00");
        var first = ((List<?>) sale.get("lineas")).getFirst();
        sale.put("lineas",List.of(first, Map.of("posicion",2,"tipoLinea","PRODUCT","codigo","A","productoId",id.toString(),
                "cantidad","0.5","precioUnitario","5","descuento","0","total","2.50")));
        receive(a,UUID.randomUUID(),sale);
        receive(a,UUID.randomUUID(),productDocument("TICKET","EUR","2025-01-02","1","35",UUID.randomUUID(),"B","BASE"));
        receive(a,UUID.randomUUID(),productDocument("TICKET","USD","2025-01-02","1","10",UUID.randomUUID(),"B","BASE"));
        var result = articles.report(JAN,null,null,null,null,"A",ArticleReport.Sort.CODE,0,10);
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.current().saleDocuments()).isOne();
            assertThat(row.current().averageQuantityPerSale()).isEqualTo("3.000");
            assertThat(row.current().amountSharePercent()).isEqualTo("30.00");
        });
    }

    @Test void documentDrillShowsSourceHeadersExcludedFactsAndCoverageAcrossFilters() {
        var a = site("Drill","B91100015");
        var b = site("Other drill","B91100026");
        var product = UUID.randomUUID();
        var ticket = UUID.randomUUID();
        var cancelled = UUID.randomUUID();
        var standalone = UUID.randomUUID();
        receive(a,ticket,productDocument("TICKET","EUR","2025-01-02","1","7",product,"A","BASE"));
        var invoice = productDocument("FACTURA_VENTA","EUR","2025-02-01","1","7",product,"A","BASE");
        invoice.put("relaciones",List.of(Map.of("tipo","FACTURA_DE","origenId",ticket.toString())));
        receive(a,UUID.randomUUID(),invoice);
        var cancel = productDocument("TICKET","EUR","2025-01-02","1","3",product,"A","BASE");
        cancel.put("estado","ANULADO");
        receive(a,cancelled,cancel);
        receive(a,standalone,productDocument("TICKET","EUR","2025-01-02","1","4",product,"A","BASE"));
        receive(a,UUID.randomUUID(),document("TICKET","PAGADO","EUR","20","A"));
        receive(a,UUID.randomUUID(),productDocument("TICKET","USD","2025-01-02","1","9",product,"A","BASE"));
        receive(b,UUID.randomUUID(),productDocument("TICKET","EUR","2025-01-02","1","11",product,"A","BASE"));
        var rows = documents.report(JAN,a.company().getId(),a.store().getId(),null,product,null,"EUR",
                DocumentReport.Sort.AMOUNT,DocumentReport.Direction.DESC,0,5);
        assertThat(rows.totalRows()).isEqualTo(3);
        assertThat(rows.rows()).extracting(DocumentReport.Row::documentId).containsExactly(ticket,standalone,cancelled);
        assertThat(rows.rows()).extracting(DocumentReport.Row::eligible).containsExactly(false,true,false);
        assertThat(rows.rows()).allMatch(row -> row.linesComplete() && row.relationshipsComplete());
        var legacy = documents.report(JAN,a.company().getId(),a.store().getId(),null,null,"A","EUR",
                DocumentReport.Sort.DATE,null,0,5);
        assertThat(legacy.rows()).singleElement().extracting(DocumentReport.Row::total).isEqualTo("20.00");
        var unknown = document("TICKET","PAGADO","EUR","8","X");
        unknown.remove("relaciones");
        unknown.put("fecha","2023-03-01");
        receive(a,UUID.randomUUID(),unknown);
        var masked = documents.report(JAN,a.company().getId(),a.store().getId(),null,product,null,"EUR",
                DocumentReport.Sort.AMOUNT,DocumentReport.Direction.DESC,0,5);
        assertThat(masked.coverage().unknownRelationships()).isOne();
        assertThat(masked.rows()).extracting(DocumentReport.Row::eligible).containsExactly(false,null,false);
        assertThat(masked.rows().get(1).total()).isEqualTo("4.00");
    }

    private Map<String,Object> productDocument(String type,String currency,String date,String quantity,String amount,
                                               UUID product,String code,String tariff) {
        var doc = document(type,"PAGADO",currency,amount,code);
        doc.put("fecha",date);
        doc.put("lineas",List.of(Map.of("posicion",1,"tipoLinea","PRODUCT","codigo",code,"productoId",product.toString(),
                "nombre","Item "+code,"cantidad",quantity,"precioUnitario",amount,"descuento","0","total",amount,"tarifa",tariff)));
        return doc;
    }

    private void receipt(Site site, String received, String type, String status, int count) {
        jdbc.update("""
                insert into saas_sync_event(event_id, company_id, store_id, entity_type, entity_id, operation, payload,
                      received_at, schema_version, payload_hash, projection_status)
                select gen_random_uuid(), ?, ?, ?, gen_random_uuid(), 'CREAR','{}', cast(? as timestamptz),1,
                       repeat('0',64),? from generate_series(1,?)
                """,site.company().getId(),site.store().getId(),type,received,status,count);
    }

    private String adminToken(String user, String permission) {
        var account = admins.saveAndFlush(new SaasAdminUser(UUID.randomUUID(),user,"unused-test-hash",true,BASE));
        UUID roleId = UUID.randomUUID();
        jdbc.update("insert into saas_admin_role(id,name,created_at) values (?,?,now())",roleId,user);
        jdbc.update("insert into saas_admin_user_role(user_id,role_id) values (?,?)",account.getId(),roleId);
        jdbc.update("insert into saas_admin_role_permission(role_id,permission_code) values (?,?)",roleId,permission);
        return "Bearer " + sessions.issue("admin",user).token();
    }

    private static Period period(String from, String to) {
        return Period.inclusive(LocalDate.parse(from), LocalDate.parse(to));
    }
}
