package com.tpverp.saas.tenant;

import static com.tpverp.saas.SaasTestData.validCif;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.access.*;
import com.tpverp.saas.admin.AdminPasswordHasher;
import com.tpverp.saas.admin.SaasSessionTokenStore;
import com.tpverp.saas.license.*;
import com.tpverp.saas.plan.PlanLimitService;
import com.tpverp.saas.plan.PlanResource;
import com.tpverp.saas.sync.*;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/** Real PostgreSQL authorization and read-model isolation in a dedicated test schema. */
@SpringBootTest(properties = {
        "spring.flyway.default-schema=tenant_store_access_test",
        "spring.datasource.hikari.schema=tenant_store_access_test",
        "spring.jpa.properties.hibernate.default_schema=tenant_store_access_test"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TenantStoreAccessPostgreSqlTest {
    private static final AtomicInteger COMPANY_NUMBER = new AtomicInteger(9700000);
    @Autowired TenantAccessService access;
    @Autowired SaasTenantUserRepository users;
    @Autowired SaasCompanyRepository companies;
    @Autowired SaasStoreRepository stores;
    @Autowired SaasLicenseRepository licenses;
    @Autowired SaasInstallationRepository installations;
    @Autowired TokenHasher tokens;
    @Autowired SyncEventService sync;
    @Autowired SaasSessionTokenStore sessions;
    @Autowired AdminPasswordHasher passwords;
    @Autowired PlanLimitService limits;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;

    @BeforeEach
    void isolatedSchema() {
        assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo("tenant_store_access_test");
    }

    @Test
    void oneIdentitySelectsTwoCompaniesAndOnlyExplicitStores() throws Exception {
        Site first = site();
        Site hidden = site(first.company(), "002");
        Site second = site();
        Site foreign = site();
        SaasTenantUser user = user(first.company());
        grant(user, first, Set.of());
        grant(user, second, Set.of());
        String token = sessions.issue("tenant", user.getUsername()).token();

        mvc.perform(get("/api/v1/tenant/access").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.companies.length()").value(2));
        mvc.perform(get("/api/v1/tenant/me").header("Authorization", bearer(token)))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/tenant/stores").header("Authorization", bearer(token))
                        .header("X-TPV-Company-Id", first.company().getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].storeId").value(first.store().getId().toString()));
        mvc.perform(get("/api/v1/tenant/stores").header("Authorization", bearer(token))
                        .param("companyId", second.company().getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].storeId").value(second.store().getId().toString()));
        mvc.perform(get("/api/v1/tenant/stores").header("Authorization", bearer(token))
                        .header("X-TPV-Company-Id", foreign.company().getId()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/tenant/stores").header("Authorization", bearer(token))
                        .header("X-TPV-Company-Id", first.company().getId())
                        .param("companyId", second.company().getId().toString()))
                .andExpect(status().isBadRequest());
        assertThat(access.access(user.getUsername()).companies().stream().flatMap(c -> c.stores().stream()))
                .extracting(TenantAccessResponse.StoreAccess::storeId).doesNotContain(hidden.store().getId());
        assertThat(users.findByCompany_IdOrderByUsernameAsc(second.company().getId()))
                .extracting(SaasTenantUser::getId).containsExactly(user.getId());
    }

    @Test
    void historicalOriginCompanyDoesNotGrantAnUnassignedIdentityAnyData() throws Exception {
        Site first = site();
        SaasTenantUser user = user(first.company());
        String token = sessions.issue("tenant", user.getUsername()).token();
        mvc.perform(get("/api/v1/tenant/access").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.companies.length()").value(0));
        mvc.perform(get("/api/v1/tenant/me").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/tenant/erp/customers").header("Authorization", bearer(token))
                        .header("X-TPV-Company-Id", first.company().getId()))
                .andExpect(status().isForbidden());
    }

    @Test
    void grantsAreRecheckedWithoutReplacingBearerSessionAndFutureStoresStayHidden() throws Exception {
        Site first = site();
        SaasTenantUser user = user(first.company());
        access.initializeAccess(user.getId(), first.company().getId(), "VIEWER", Set.of(first.store().getId()));
        String token = sessions.issue("tenant", user.getUsername()).token();
        Site future = site(first.company(), "002");
        mvc.perform(get("/api/v1/tenant/stores").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/v1/tenant/stores/{id}/stock", future.store().getId()).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        access.replace(user.getUsername(), first.company().getId(), new UpdateTenantAccessRequest("VIEWER", Set.of(), Set.of()));
        mvc.perform(get("/api/v1/tenant/stores/{id}/stock", first.store().getId()).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        access.revoke(user.getUsername(), first.company().getId());
        mvc.perform(get("/api/v1/tenant/stores").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/tenant/access").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.companies.length()").value(0));
    }

    @Test
    void storeAccessNeverGrantsCompanyMastersInvoicesSupportOrLicenses() throws Exception {
        Site first = site();
        SaasTenantUser user = user(first.company());
        grant(user, first, Set.of());
        String token = sessions.issue("tenant", user.getUsername()).token();
        for (String path : new String[]{"/erp/customers", "/erp/products/search", "/invoices", "/tickets", "/licenses"}) {
            mvc.perform(get("/api/v1/tenant" + path).header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/tenant/dashboard").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stores").value(1))
                .andExpect(jsonPath("$.installations").value(1))
                .andExpect(jsonPath("$.licenses").doesNotExist()).andExpect(jsonPath("$.openTickets").doesNotExist())
                .andExpect(jsonPath("$.monthlyPrice").doesNotExist());
        grant(user, first, Set.of(TenantCompanyPrivilege.READ_MASTERS));
        mvc.perform(get("/api/v1/tenant/erp/customers").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/tenant/erp/customers").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        assertThatThrownBy(() -> access.replace(user.getUsername(), first.company().getId(),
                new UpdateTenantAccessRequest("VIEWER", EnumSet.allOf(TenantCompanyPrivilege.class), Set.of(first.store().getId()))))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void realDocumentProjectionAndStockQueriesNeverEscapeAssignedStores() throws Exception {
        Site allowed = site();
        Site hidden = site(allowed.company(), "002");
        Site foreign = site();
        publishDocument(allowed, "ALLOWED");
        publishDocument(hidden, "HIDDEN");
        publishDocument(foreign, "FOREIGN");
        SaasTenantUser user = user(allowed.company());
        grant(user, allowed, Set.of());
        String token = sessions.issue("tenant", user.getUsername()).token();
        var query = new LinkedHashMap<String, Object>();
        query.put("storeIds", Set.of()); query.put("types", Set.of("TICKET"));
        query.put("statuses", Set.of("PAGADO")); query.put("size", 20);
        mvc.perform(post("/api/v1/tenant/documents/page").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(query)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].number").value("ALLOWED"));
        query.put("storeIds", Set.of(hidden.store().getId()));
        mvc.perform(post("/api/v1/tenant/documents/page").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(query)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/tenant/stores/{id}/stock", foreign.store().getId()).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/tenant/stores/{id}/stock", allowed.store().getId()).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/tenant/stores/{id}/sync-status", allowed.store().getId()).header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    @Test
    void crossCompanyGrantIsAtomicAndPlanUsageCountsMembershipNotOrigin() {
        Site first = site();
        Site second = site();
        SaasTenantUser user = user(first.company());
        grant(user, first, Set.of());
        grant(user, second, Set.of());
        assertThat(limits.usage(first.company().getId()).usage().get(PlanResource.TENANT_USERS)).isEqualTo(1);
        assertThat(limits.usage(second.company().getId()).usage().get(PlanResource.TENANT_USERS)).isEqualTo(1);
        assertThatThrownBy(() -> access.replace(user.getUsername(), first.company().getId(),
                new UpdateTenantAccessRequest("MANAGER", Set.of(TenantCompanyPrivilege.READ_BILLING), Set.of(second.store().getId()))))
                .isInstanceOf(ResponseStatusException.class);
        var existing = access.access(user.getUsername()).companies().stream()
                .filter(c -> c.companyId().equals(first.company().getId())).findFirst().orElseThrow();
        assertThat(existing.roleName()).isEqualTo("VIEWER");
        assertThat(existing.stores()).extracting(TenantAccessResponse.StoreAccess::storeId).containsExactly(first.store().getId());
        access.revoke(user.getUsername(), second.company().getId());
        assertThat(limits.usage(second.company().getId()).usage().get(PlanResource.TENANT_USERS)).isZero();
        assertThatThrownBy(() -> access.replace(user.getUsername(), second.company().getId(),
                new UpdateTenantAccessRequest("OWNER", Set.of(), Set.of(second.store().getId()))))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void tenantCannotUseAdministrativeAccessManagement() throws Exception {
        Site first = site();
        SaasTenantUser user = user(first.company());
        grant(user, first, Set.of());
        String token = sessions.issue("tenant", user.getUsername()).token();
        mvc.perform(put("/api/v1/admin/tenant-users/{name}/access/companies/{company}", user.getUsername(), first.company().getId())
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new UpdateTenantAccessRequest("MANAGER",
                                EnumSet.allOf(TenantCompanyPrivilege.class), Set.of(first.store().getId())))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void companyQuotaAppliesToNewMembershipAndReactivatingAnExistingIdentity() {
        Site first = site();
        jdbc.update("""
                insert into saas_company_operations(company_id,plan_name,billing_status,support_status,updated_at)
                values (?,'BASIC','PENDIENTE','NORMAL',now())
                """, first.company().getId());
        for (int index = 0; index < 3; index++) grant(user(first.company()), first, Set.of());
        SaasTenantUser extra = user(first.company());
        assertThatThrownBy(() -> grant(extra, first, Set.of())).isInstanceOf(ResponseStatusException.class);
        assertThat(access.access(extra.getUsername()).companies()).isEmpty();
        SaasTenantUser inactive = users.saveAndFlush(new SaasTenantUser(UUID.randomUUID(), first.company(),
                "inactive-" + UUID.randomUUID(), passwords.hash("Access-test-42"), "VIEWER", false, Instant.now()));
        grant(inactive, first, Set.of());
        assertThatThrownBy(() -> jdbc.update("update saas_tenant_user set active = true where id = ?", inactive.getId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("select active from saas_tenant_user where id = ?", Boolean.class, inactive.getId())).isFalse();
        assertThat(limits.usage(first.company().getId()).usage().get(PlanResource.TENANT_USERS)).isEqualTo(3);
    }

    private void grant(SaasTenantUser user, Site site, Set<TenantCompanyPrivilege> privileges) {
        access.replace(user.getUsername(), site.company().getId(),
                new UpdateTenantAccessRequest("VIEWER", privileges, Set.of(site.store().getId())));
    }

    private SaasTenantUser user(SaasCompany company) {
        return users.saveAndFlush(new SaasTenantUser(UUID.randomUUID(), company,
                "access-" + UUID.randomUUID(), passwords.hash("Access-test-42"), "VIEWER", true, Instant.now()));
    }

    private Site site() {
        return site(companies.saveAndFlush(new SaasCompany(UUID.randomUUID(), "Access test company",
                validCif("B" + COMPANY_NUMBER.getAndIncrement() + "0"), TaxpayerType.SOCIEDAD,
                TaxRegime.IVA, Instant.now())), "001");
    }

    private Site site(SaasCompany company, String code) {
        SaasStore store = stores.saveAndFlush(new SaasStore(UUID.randomUUID(), company, code,
                "Access test store", "Atlantic/Canary", Instant.now()));
        SaasLicense license = licenses.saveAndFlush(new SaasLicense(UUID.randomUUID(), company,
                "ACCESS-" + UUID.randomUUID(), Instant.now().plusSeconds(86400), 1, 1, Instant.now()));
        String token = tokens.newToken();
        SaasInstallation installation = installations.saveAndFlush(new SaasInstallation(UUID.randomUUID(), company,
                store, license, UUID.randomUUID(), "ACCESS", null, tokens.hash(token), Instant.now()));
        return new Site(company, store, installation, token);
    }

    private void publishDocument(Site site, String number) {
        Map<String, Object> snapshot = Map.of("schemaVersion", 2, "sourceRevision", 1,
                "tipo", "TICKET", "estado", "PAGADO", "numero", number, "fecha", "2026-01-01",
                "subtotal", "10.00", "impuestos", "2.10", "total", "12.10", "moneda", "EUR");
        sync.receive(new SyncEventRequest(UUID.randomUUID(), site.company().getId(), site.store().getId(),
                null, "DOCUMENTO", UUID.randomUUID(), SyncOperation.ACTUALIZAR, snapshot), site.token());
    }

    private static String bearer(String token) { return "Bearer " + token; }
    private record Site(SaasCompany company, SaasStore store, SaasInstallation installation, String token) { }
}
