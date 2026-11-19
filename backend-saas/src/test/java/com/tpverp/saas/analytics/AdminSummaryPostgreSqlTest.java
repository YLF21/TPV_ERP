package com.tpverp.saas.analytics;

import static com.tpverp.saas.analytics.AdminSummary.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tpverp.saas.admin.AdminPasswordHasher;
import com.tpverp.saas.admin.SaasAdminUser;
import com.tpverp.saas.admin.SaasAdminUserRepository;
import com.tpverp.saas.license.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"spring.flyway.default-schema=admin_summary_test",
        "spring.datasource.hikari.schema=admin_summary_test",
        "spring.jpa.properties.hibernate.default_schema=admin_summary_test"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class AdminSummaryPostgreSqlTest extends CommercialProjectionTestSupport {
    private static final String PATH = "/api/v1/admin/summary";
    @Autowired AdminSummaryService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired SaasAdminUserRepository admins;
    @Autowired AdminPasswordHasher passwords;
    @Autowired MockMvc mvc;

    @Test void httpRequiresAdminReadPermissionAndReturnsRealEmptyCoverage() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        admins.saveAndFlush(new SaasAdminUser(UUID.randomUUID(), "summary-no-role", passwords.hash("summary-password"),
                true, BASE));
        mvc.perform(get(PATH).header("Authorization", basic("summary-no-role", "summary-password")))
                .andExpect(status().isForbidden());
        mvc.perform(get(PATH).header("Authorization", basic("viewer", "admin")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.companies").value(0)).andExpect(jsonPath("$.documents.total").value(0))
                .andExpect(jsonPath("$.documents.amounts").isEmpty()).andExpect(jsonPath("$.recentLicenses").isEmpty())
                .andExpect(jsonPath("$.events.byStatus.length()").value(4))
                .andExpect(jsonPath("$.events.firstReceivedAt").isEmpty()).andExpect(jsonPath("$.events.lastReceivedAt").isEmpty());
    }

    @Test void readsAllEventsAndLicensesAndOrdersRecentLicensesByCreation() {
        Site site = site("A", "B91000010");
        jdbc.update("""
                insert into saas_company_operations(company_id,plan_name,billing_status,support_status,updated_at)
                values (?,'PREMIUM','PAGADO','NORMAL',now())
                """, site.company().getId());
        var inactive = new SaasStore(UUID.randomUUID(), site.company(), "002", "Inactive", "UTC", BASE);
        inactive.setActive(false);
        em.persist(inactive);
        for (int i = 1; i <= 10; i++) {
            var license = new SaasLicense(UUID.randomUUID(), site.company(), "L-" + i, i >= 9 ? BASE : FUTURE,
                    1, 0, BASE.plusSeconds(i));
            if (i == 9) license.block();
            if (i == 10) license.assignStore(site.store());
            em.persist(license);
        }
        em.flush();
        jdbc.update("""
                insert into saas_sync_event(event_id, company_id, store_id, entity_type, entity_id, operation, payload,
                      received_at, schema_version, payload_hash, projection_status)
                select gen_random_uuid(), ?, ?, case when n<=200 then 'TYPE_A' else 'TYPE_B' end,
                       gen_random_uuid(), 'CREAR', '{}', timestamptz '2025-01-01 00:00:00+00' + n * interval '1 second',
                       1, repeat('0',64), (array['RECEIVED','PROJECTED','IGNORED','ERROR'])[1+n%4]
                from generate_series(1,204) n
                """, site.company().getId(), site.store().getId());
        AdminSummary result = service.summary();
        assertThat(result.companies()).isEqualTo(1);
        assertThat(result.stores()).isEqualTo(new Stores(2, 1));
        assertThat(result.installations()).isEqualTo(1);
        assertThat(result.licenses()).isEqualTo(new Licenses(11, 9, 1, 1));
        assertThat(result.recentLicenses()).extracting(RecentLicense::reference)
                .containsExactly("L-10", "L-9", "L-8", "L-7", "L-6", "L-5", "L-4", "L-3");
        assertThat(result.recentLicenses().getFirst()).satisfies(recent -> {
            assertThat(recent.companyName()).isEqualTo("A");
            assertThat(recent.storeCount()).isOne();
            assertThat(recent.storeName()).isEqualTo("Store A");
            assertThat(recent.createdAt()).isEqualTo(BASE.plusSeconds(10));
            assertThat(recent.validUntil()).isEqualTo(BASE);
            assertThat(recent.status()).isEqualTo(LicenseSaasStatus.CADUCADA);
        });
        assertThat(result.recentLicenses().get(1).status()).isEqualTo(LicenseSaasStatus.BLOQUEADA_MANUAL);
        assertThat(result.recentLicenses().get(1).storeCount()).isZero();
        assertThat(result.recentLicenses().get(1).storeName()).isNull();
        assertThat(result.events().total()).isEqualTo(204);
        assertThat(result.events().byType()).containsExactly(new Count("TYPE_A", 200), new Count("TYPE_B", 4));
        assertThat(result.events().byStatus()).containsExactly(new Count("RECEIVED", 51), new Count("PROJECTED", 51),
                new Count("IGNORED", 51), new Count("ERROR", 51));
        assertThat(result.events().firstReceivedAt()).isEqualTo(BASE.plusSeconds(1));
        assertThat(result.events().lastReceivedAt()).isEqualTo(BASE.plusSeconds(204));
    }

    @Test void countsDistinctHistoricalStoreIdentitiesAcrossInstallationsAndPairingCodes() {
        Site site = site("A", "B91000010");
        var license = em.createQuery("from SaasLicense where reference=:reference", SaasLicense.class)
                .setParameter("reference", "SITE-A").getSingleResult();
        assertThat(license.getStore()).isNull();
        em.persist(new SaasPairingCode(UUID.randomUUID(), site.company(), site.store(), license, "PAIR-A", FUTURE, BASE));
        em.flush();
        assertThat(service.summary().recentLicenses()).singleElement().satisfies(row -> {
            assertThat(row.storeCount()).isOne();
            assertThat(row.storeName()).isEqualTo("Store A");
        });
        var other = new SaasStore(UUID.randomUUID(), site.company(), "002", "Store A", "UTC", BASE);
        other.setActive(false);
        em.persist(other);
        var historicalCode = new SaasPairingCode(UUID.randomUUID(), site.company(), other, license, "PAIR-B", FUTURE, BASE);
        historicalCode.revoke(BASE.plusSeconds(1), "LEGACY_REPLACED");
        em.persist(historicalCode);
        em.flush();
        assertThat(service.summary().recentLicenses()).singleElement().satisfies(row -> {
            assertThat(row.storeCount()).isEqualTo(2);
            assertThat(row.storeName()).isNull();
        });
    }

    @Test void aggregatesLatestSnapshotsSkipsConversionsAndWithholdsUncertainAmounts() {
        Site a = site("A", "B91000010");
        Site b = site("B", "B91000020");
        UUID ticket = UUID.randomUUID();
        var sale = document("TICKET", "PAGADO", "EUR", "11.10", "001");
        receive(a, ticket, sale);
        sale.put("sourceRevision", 2);
        sale.put("total", "12.10");
        var secondRevision = receive(a, ticket, sale);
        sync.receive(secondRevision, a.token()); // Replay the same receipt.
        var invoice = document("FACTURA_VENTA", "PENDIENTE", "EUR", "13.20", "001");
        invoice.put("relaciones", List.of(Map.of("tipo", "FACTURA_DE", "origenId", ticket.toString())));
        UUID invoiceId = UUID.randomUUID();
        receive(a, invoiceId, invoice);
        var refund = document("RECTIFICATIVA_VENTA", "PAGADO", "EUR", "-1.20", "1");
        refund.put("relaciones", List.of(Map.of("tipo", "RECTIFICA", "origenId", invoiceId.toString())));
        receive(a, UUID.randomUUID(), refund);
        receive(b, UUID.randomUUID(), document("TICKET", "PAGADO", "USD", "32", "001"));
        receive(b, UUID.randomUUID(), document("TICKET", "ANULADO", "USD", "45", "001"));
        var missingLines = document("ALBARAN_VENTA", "CONFIRMADO", "EUR", "21", "OLD");
        missingLines.remove("lineas");
        receive(a, UUID.randomUUID(), missingLines);
        em.flush();
        var result = service.summary();
        assertThat(result.events().total()).isEqualTo(7);
        var docs = result.documents();
        assertThat(docs.total()).isEqualTo(6);
        assertThat(docs.byType()).containsExactlyInAnyOrder(new Count("TICKET", 3), new Count("FACTURA_VENTA", 1),
                new Count("ALBARAN_VENTA", 1), new Count("RECTIFICATIVA_VENTA", 1));
        assertThat(docs.byStatus()).containsExactlyInAnyOrder(new Count("PAGADO", 3), new Count("PENDIENTE", 1),
                new Count("ANULADO", 1), new Count("CONFIRMADO", 1));
        assertThat(docs.storesWithDocuments()).isEqualTo(2);
        assertThat(docs.observedProductReferences()).isEqualTo(3);
        assertThat(docs.incompleteDocuments()).isEqualTo(1);
        assertThat(docs.unknownRelationships()).isZero();
        assertThat(docs.amounts()).containsExactly(new Amount("EUR", 3, "33.00"), new Amount("USD", 1, "32.00"));

        invoice.put("sourceRevision", 2);
        invoice.put("estado", "ANULADO");
        receive(a, invoiceId, invoice);
        assertThat(service.summary().documents().amounts()).contains(new Amount("EUR", 3, "31.90"));
        var legacy = document("TICKET", "PAGADO", "EUR", "10", "LEGACY");
        legacy.remove("relaciones");
        legacy.remove("lineas");
        receive(a, UUID.randomUUID(), legacy);
        refund.put("sourceRevision", 2);
        refund.put("relaciones", List.of(Map.of("tipo", "RECTIFICA", "origenId", UUID.randomUUID().toString())));
        receive(a, UUID.randomUUID(), refund);
        var incomplete = service.summary().documents();
        assertThat(incomplete.unknownRelationships()).isEqualTo(2);
        assertThat(incomplete.incompleteDocuments()).isEqualTo(3);
        assertThat(incomplete.amounts()).isEmpty();
    }

    @Test void filtersLifetimeAndUtcActivityAndReadsOnlyLatestCompleteCatalogueFromActiveInstallation() {
        var a = site("Selected","B91000201");
        var b = site("Uncovered","B91000212");
        jdbc.update("update saas_store set store_address='{\"pais\":\"ES\",\"codigoPostal\":\"35001\"}' where id=?",
                a.store().getId());
        receive(a,UUID.randomUUID(),document("TICKET","PAGADO","EUR","10.25","001"));
        receive(b,UUID.randomUUID(),document("TICKET","PAGADO","USD","99","001"));
        jdbc.update("""
                insert into saas_inventory_snapshot(company_id,store_id,installation_id,snapshot_id,captured_at,
                     chunk_count,product_count,warehouse_count,balance_count,completed_at,active_product_count)
                select i.company_id,i.store_id,i.id,gen_random_uuid(),timestamptz '2025-01-01T00:00:00Z'+n*interval '1 day',
                       1,5*n,0,0,case when n<3 then now() end,4*n
                from saas_installation i cross join generate_series(1,3) n where i.store_id=?
                """,a.store().getId());
        var from = java.time.LocalDate.parse("2025-01-01");
        var result = service.summary(from,from.plusDays(2),null,null,"35");
        assertThat(result.companies()).isOne();
        assertThat(result.licenses().total()).isOne();
        assertThat(result.installations()).isOne();
        assertThat(result.newCompanies()).isOne();
        assertThat(result.newStores()).isOne();
        assertThat(result.newLicenses()).isOne();
        assertThat(result.documents().total()).isOne();
        assertThat(result.events().total()).isOne();
        assertThat(result.daily()).hasSize(3);
        assertThat(result.daily().getFirst().currencies().getFirst().amount()).isEqualTo("10.25");
        assertThat(result.catalogue().products()).isEqualTo(10);
        assertThat(result.catalogue().activeProducts()).isEqualTo(8);
        assertThat(result.catalogue().coveredStores()).isOne();
        assertThat(result.recentLicenses().getFirst().province()).isEqualTo("35");
        var all = service.summary(from.plusDays(1),from.plusDays(2),null,null,null);
        assertThat(all.newCompanies()).isZero();
        assertThat(all.companies()).isEqualTo(2);
        assertThat(all.catalogue().coveredStores()).isOne();
        assertThat(all.catalogue().stores()).isEqualTo(2);
        jdbc.update("""
                update saas_installation set active=false, revoked_at=now(), revoked_by='summary-test',
                       revocation_reason='Replacement installation' where store_id=?
                """,a.store().getId());
        assertThat(service.summary(from,from,null,null,"35").catalogue().products()).isNull();
    }

    private static String basic(String username, String password) {
        return "Basic " + HttpHeaders.encodeBasicAuth(username, password, null);
    }

}
