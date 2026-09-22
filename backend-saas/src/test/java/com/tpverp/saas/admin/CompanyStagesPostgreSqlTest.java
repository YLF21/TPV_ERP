package com.tpverp.saas.admin;

import static com.tpverp.saas.SaasTestData.fiscalAddress;
import static com.tpverp.saas.SaasTestData.companyOwners;
import static com.tpverp.saas.SaasTestData.validCif;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.license.CommercialProfile;
import com.tpverp.saas.license.TaxpayerType;
import com.tpverp.saas.license.TaxRegime;
import com.tpverp.saas.license.StoreBillingPeriod;
import com.tpverp.saas.stores.StoreAdministrationService;
import com.tpverp.saas.stores.LicenseWorkspaceService;
import com.tpverp.saas.stores.StoreWorkspaceApi;
import java.math.BigDecimal;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CompanyStagesPostgreSqlTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminService admin;
    @Autowired StoreAdministrationService stores;
    @Autowired LicenseWorkspaceService licenses;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void companyIsIndependentAndCanBeListedAndEditedBeforeProvisioning() throws Exception {
        var request = new CreateCompanyRequest("  Sociedad   independiente  ", validCif("B76843210"),
                TaxpayerType.SOCIEDAD, CommercialProfile.MAYORISTA, fiscalAddress(), companyOwners());
        var created = mvc.perform(post("/api/v1/admin/companies").header("Authorization", auth("admin"))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk()).andReturn();
        var company = mapper.readValue(created.getResponse().getContentAsString(), CompanySummaryResponse.class);
        assertThat(company.companyName()).isEqualTo("Sociedad independiente");
        var payload = mapper.readTree(created.getResponse().getContentAsString());
        assertThat(payload.has("pairingCode")).isFalse();
        assertThat(payload.has("tenantInitialPassword")).isFalse();
        for (String table : new String[]{"saas_store", "saas_license", "saas_pairing_code", "saas_tenant_user"}) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where company_id = ?",
                    Integer.class, company.companyId())).as(table).isZero();
        }
        assertThat(jdbc.queryForObject("select tax_regime from saas_company where id = ?",
                String.class, company.companyId())).isNull();
        var listed = mvc.perform(get("/api/v1/admin/companies").header("Authorization", auth("admin")))
                .andExpect(status().isOk()).andReturn();
        assertThat(mapper.readValue(listed.getResponse().getContentAsString(), CompanySummaryResponse[].class))
                .anyMatch(row -> row.companyId().equals(company.companyId()));
        var edited = new EditCompanyDataRequest("Sociedad editada", TaxpayerType.SOCIEDAD,
                CommercialProfile.MINORISTA, fiscalAddress());
        mvc.perform(put("/api/v1/admin/companies/{id}", company.companyId())
                .header("Authorization", auth("admin")).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(edited))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select name from saas_company where id = ?", String.class,
                company.companyId())).isEqualTo("Sociedad editada");
    }

    @Test
    void contactUpdatePreservesExistingCommercialHistory() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into saas_company(id,name,tax_id,taxpayer_type,commercial_profile,created_at) values (?,? ,?,'SOCIEDAD','MAYORISTA',now())",
                id, "Contacto independiente", validCif("B76843220"));
        jdbc.update("insert into saas_company_operations(company_id,plan_name,billing_status,monthly_price,support_status,updated_at) values (?,'STANDARD','PAGADO','73.25','NORMAL',now())", id);
        mvc.perform(put("/api/v1/admin/companies/{id}/operations", id).header("Authorization", auth("admin"))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(
                        Map.of("contactName", "Contacto", "contactEmail", "contacto@example.test", "notes", "Nota"))))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select monthly_price from saas_company_operations where company_id=?", String.class, id)).isEqualTo("73.25");
        assertThat(jdbc.queryForObject("select billing_status from saas_company_operations where company_id=?", String.class, id)).isEqualTo("PAGADO");
    }

    @Test
    void taxpayerTypeCannotChangeWhileTheFirstLicenseIsBeingIssued() throws Exception {
        var company = admin.createCompany(new CreateCompanyRequest("Concurrent company", validCif("B76843240"),
                TaxpayerType.SOCIEDAD, CommercialProfile.MAYORISTA, fiscalAddress(), companyOwners()));
        var store = stores.create(company.companyId(), new StoreWorkspaceApi.CreateStore("001", "Concurrent store",
                fiscalAddress(), "Atlantic/Canary", TaxRegime.IVA, new BigDecimal("29.90"), StoreBillingPeriod.MONTHLY,
                1, 0, Instant.parse("2099-01-01T00:00:00Z")));
        var change = new EditCompanyDataRequest(company.companyName(), TaxpayerType.AUTONOMO,
                CommercialProfile.MAYORISTA, fiscalAddress());
        var attempts = new ArrayList<Future<CompanySummaryResponse>>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                jdbc.query("select pg_advisory_xact_lock(hashtextextended(?::text, 0))", rows -> { }, company.companyId());
                attempts.add(executor.submit(() -> admin.editCompany(company.companyId(), change)));
                awaitCompanyLock("Company editing must wait for license issuance");
                licenses.create(new StoreWorkspaceApi.CreateLicense(store.id()));
            });
            assertThatThrownBy(() -> attempts.getFirst().get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ResponseStatusException.class)
                    .satisfies(error -> assertThat(((ResponseStatusException) error.getCause()).getStatusCode().value()).isEqualTo(409));
        }
        assertThat(jdbc.queryForObject("select taxpayer_type from saas_company where id=?", String.class, company.companyId()))
                .isEqualTo("SOCIEDAD");
    }

    @Test
    void fiscalAddressEditingCannotRestoreStaleStoreLicenseTerms() throws Exception {
        var company = admin.createCompany(new CreateCompanyRequest("Concurrent fiscal address", validCif("B76843250"),
                TaxpayerType.SOCIEDAD, CommercialProfile.MAYORISTA, fiscalAddress(), companyOwners()));
        Instant expiry = Instant.parse("2099-01-01T00:00:00Z");
        var store = stores.create(company.companyId(), new StoreWorkspaceApi.CreateStore("001", "Concurrent store",
                fiscalAddress(), "Atlantic/Canary", TaxRegime.IVA, new BigDecimal("29.90"), StoreBillingPeriod.MONTHLY, 1, 0, expiry));
        var license = licenses.create(new StoreWorkspaceApi.CreateLicense(store.id()));
        var address = new java.util.LinkedHashMap<>(fiscalAddress());
        address.put("linea1", "Calle actualizada 123");
        var change = new UpdateFiscalProvisioningRequest(address, List.of(
                new UpdateFiscalProvisioningRequest.StoreProvisioning(store.id(), address, "Atlantic/Canary")));
        var attempts = new ArrayList<Future<FiscalProvisioningResponse>>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                jdbc.query("select pg_advisory_xact_lock(hashtextextended(?::text, 0))", rows -> { }, company.companyId());
                attempts.add(executor.submit(() -> admin.updateFiscalProvisioning(company.companyId(), change)));
                awaitCompanyLock("Fiscal editing must wait for store configuration");
                stores.update(store.id(), new StoreWorkspaceApi.UpdateStore(store.name(), fiscalAddress(), store.timeZoneId(), true,
                        TaxRegime.IVA, new BigDecimal("299.00"), StoreBillingPeriod.ANNUAL, 4, 2, expiry.plusSeconds(86400)), true);
            });
            attempts.getFirst().get(10, TimeUnit.SECONDS);
        }
        var configured = stores.list(company.companyId(), "", null, 0, 25).items().getFirst();
        assertThat(configured.servicePrice()).isEqualByComparingTo("299.00");
        assertThat(configured.billingPeriod()).isEqualTo(StoreBillingPeriod.ANNUAL);
        assertThat(configured.maxWindows()).isEqualTo(4);
        assertThat(configured.maxPda()).isEqualTo(2);
        assertThat(configured.validUntil()).isEqualTo(expiry.plusSeconds(86400));
        assertThat(configured.storeAddress().get("linea1")).isEqualTo("Calle actualizada 123");
        assertThat(jdbc.queryForObject("select max_windows from saas_license where id=?", Integer.class, license.id())).isEqualTo(4);
    }

    private void awaitCompanyLock(String description) {
        boolean waitingForCompany = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && !waitingForCompany) {
            jdbc.query("select pg_stat_clear_snapshot()", rows -> { });
            waitingForCompany = Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from pg_stat_activity where pid<>pg_backend_pid()
                        and wait_event_type='Lock' and wait_event='advisory'
                        and query like '%pg_advisory_xact_lock%')
                    """, Boolean.class));
            if (!waitingForCompany) {
                try { Thread.sleep(20); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            }
        }
        assertThat(waitingForCompany).as(description).isTrue();
    }

    @Test
    void monthlyEquivalentUsesStorePeriodsAndDoesNotInventMissingPrices() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into saas_company(id,name,tax_id,taxpayer_type,commercial_profile,created_at) values (?,? ,?,'SOCIEDAD','MAYORISTA',now())",
                id, "Precios por tienda", validCif("B76843230"));
        jdbc.update("insert into saas_company_operations(company_id,plan_name,billing_status,monthly_price,support_status,updated_at) values (?,'STANDARD','PAGADO','999.99','NORMAL',now())", id);
        jdbc.update("insert into saas_store(id,company_id,code,name,tax_regime,service_price,billing_period,created_at) values (?,?,'001','Anual','IGIC',120.12,'ANNUAL',now()),(?,?,'002','Mensual','IVA',20.02,'MONTHLY',now())",
                UUID.randomUUID(), id, UUID.randomUUID(), id);
        var result = mvc.perform(get("/api/v1/admin/billing-summary").header("Authorization", auth("admin")))
                .andExpect(status().isOk()).andReturn();
        var summary = mapper.readValue(result.getResponse().getContentAsString(), BillingSummaryResponse.class);
        assertThat(summary.companies()).filteredOn(row -> row.companyId().equals(id)).singleElement()
                .satisfies(row -> assertThat(row.monthlyPrice()).isEqualTo("30.03"));
        jdbc.update("insert into saas_store(id,company_id,code,name,tax_regime,created_at) values (?,?,'003','Pendiente','IVA',now())", UUID.randomUUID(), id);
        var incomplete = mvc.perform(get("/api/v1/admin/billing-summary").header("Authorization", auth("admin")))
                .andExpect(status().isOk()).andReturn();
        assertThat(mapper.readValue(incomplete.getResponse().getContentAsString(), BillingSummaryResponse.class).companies())
                .filteredOn(row -> row.companyId().equals(id)).singleElement()
                .satisfies(row -> assertThat(row.monthlyPrice()).isNull());
    }

    private static String auth(String username) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":admin").getBytes(StandardCharsets.UTF_8));
    }
}
