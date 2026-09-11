package com.tpverp.saas.customer;

import static com.tpverp.saas.SaasTestData.validCif;
import static com.tpverp.saas.customer.CustomerAdoptionApi.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.admin.AdminService;
import com.tpverp.saas.admin.CreateErpCustomerRequest;
import com.tpverp.saas.license.*;
import com.tpverp.saas.sync.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {
        "spring.flyway.default-schema=customer_adoption_test",
        "spring.datasource.hikari.schema=customer_adoption_test",
        "spring.jpa.properties.hibernate.default_schema=customer_adoption_test"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerAdoptionPostgreSqlTest {
    private static final AtomicInteger COMPANY_NUMBER = new AtomicInteger(9400000);
    @Autowired CustomerAdoptionService service;
    @Autowired CustomerIdentityService identities;
    @Autowired SyncEventService sync;
    @Autowired SaasSyncEventRepository events;
    @Autowired SaasCompanyRepository companies;
    @Autowired SaasStoreRepository stores;
    @Autowired SaasLicenseRepository licenses;
    @Autowired SaasInstallationRepository installations;
    @Autowired TokenHasher tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired AdminService admin;
    @Autowired MockMvc mvc;

    @Test void exactNormalizedLookupReturnsActualTypeAndAdministrativeRevisionZeroWithoutWrites() {
        var site = site();
        UUID customer = master(site);
        var profile = service.lookup(new Lookup(site.company().getId(), site.store().getId(), "DNI", "12 345-678z"), site.token());
        assertThat(profile.customerId()).isEqualTo(customer);
        assertThat(profile.documentType()).isEqualTo("NIF");
        assertThat(profile.documentNumber()).isEqualTo("12345678Z");
        assertThat(profile.revision()).isZero();
        assertThat(profile.centralCode()).isEqualTo("CENTRAL-001");
        assertThat(profile.localCustomerId()).isNull();
        assertThat(count("saas_customer_adoption_operation", site)).isZero();
        assertThat(count("saas_customer_identity_link", site)).isZero();
    }

    @Test void httpLookupIsInstallationScopedAndNotFoundIsAnExplicit404() throws Exception {
        var site = site(); master(site);
        var foreign = site();
        var query = new Lookup(site.company().getId(), site.store().getId(), "NIF", "12345678Z");
        mvc.perform(post("/api/v1/customer-identities/lookup").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(query))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/customer-identities/lookup").header("X-TPV-Installation-Token", foreign.token())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(query)))
                .andExpect(status().isUnauthorized());
        var missing = new Lookup(foreign.company().getId(), foreign.store().getId(), "NIF", "12345678Z");
        mvc.perform(post("/api/v1/customer-identities/lookup").header("X-TPV-Installation-Token", foreign.token())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(missing)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CUSTOMER_CENTRAL_NOT_FOUND"));
    }

    @Test void httpSelectedRevisionDoesNotRoundFractionalInput() throws Exception {
        var site = site(); var request = reserve(site, master(site));
        String json = mapper.writeValueAsString(request).replace("\"expectedRevision\":0", "\"expectedRevision\":0.5");
        mvc.perform(post("/api/v1/customer-identities/adoptions").header("X-TPV-Installation-Token", site.token())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isBadRequest());
        assertThat(count("saas_customer_adoption_operation", site)).isZero();
    }

    @Test void reservationIsStableAndOnlyCommittedOutboxCreatesLinksAcrossInstallationsWithoutChangingMaster() {
        var first = site(); var second = site(first.company(), "002");
        UUID customer = master(first);
        var a = reserve(first, customer); var b = reserve(second, customer);
        var before = masterRow(customer);
        var reserved = service.reserve(a, first.token());
        assertThat(service.reserve(a, first.token())).isEqualTo(reserved);
        service.reserve(b, second.token());
        assertThat(count("saas_customer_identity_link", first)).isZero();
        UUID event = finish(first, a);
        finish(second, b);
        finish(first, a);
        assertThat(events.findById(event).orElseThrow().getProjectionStatus()).isEqualTo(SaasSyncEvent.ProjectionStatus.PROJECTED);
        assertThat(count("saas_erp_customer", first)).isEqualTo(1);
        assertThat(count("saas_customer_identity_link", first)).isEqualTo(2);
        assertThat(masterRow(customer)).isEqualTo(before);
        assertThat(service.lookup(new Lookup(second.company().getId(), second.store().getId(), "NIF", "12345678Z"), second.token())
                .localCustomerId()).isEqualTo(b.localCustomerId());
        assertThat(jdbc.queryForObject("select status from saas_customer_adoption_operation where operation_id=?", String.class, a.operationId()))
                .isEqualTo("COMMITTED");
    }

    @Test void sameInstallationCannotUseTwoLocalCopiesOrReassignOneLocalIdAndLinkIsImmutable() {
        var site = site(); UUID customer = master(site);
        var first = reserve(site, customer); service.reserve(first, site.token()); finish(site, first);
        conflict(() -> service.reserve(reserve(site, customer), site.token()));
        UUID other = admin.createErpCustomer(site.company().getId(), new CreateErpCustomerRequest("OTHER", "Other", "X1234567L", null, null, "NIE")).id();
        var reassigned = new Reserve(first.companyId(), first.storeId(), UUID.randomUUID(), first.localCustomerId(),
                other, 0L, "NIE", "X1234567L");
        conflict(() -> service.reserve(reassigned, site.token()));
        assertThatThrownBy(() -> jdbc.update("update saas_customer_identity_link set customer_id=? where installation_id=?", other, site.installation().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from saas_customer_identity_link where installation_id=?", site.installation().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("saas_customer_identity_link", site)).isEqualTo(1);
    }

    @Test void cancellationTombstoneBlocksLateReserveAndCommittedLinkCannotBeCancelled() {
        var site = site(); UUID customer = master(site);
        var cancelled = reserve(site, customer);
        var owner = owner(cancelled);
        service.cancel(cancelled.operationId(), owner, site.token());
        service.cancel(cancelled.operationId(), owner, site.token());
        conflict(() -> service.reserve(cancelled, site.token()));
        conflict(() -> finish(site, cancelled));
        assertThat(count("saas_customer_identity_link", site)).isZero();
        var committed = reserve(site, customer); service.reserve(committed, site.token()); finish(site, committed);
        conflict(() -> service.cancel(committed.operationId(), owner(committed), site.token()));
    }

    @Test void staleSelectionChangedRetryWrongTypeAndForeignCompanyFailWithoutLinking() {
        var site = site(); UUID customer = master(site);
        var request = reserve(site, customer);
        conflict(() -> service.reserve(new Reserve(request.companyId(), request.storeId(), request.operationId(), request.localCustomerId(),
                customer, 1L, "NIF", "12345678Z"), site.token()));
        conflict(() -> service.reserve(new Reserve(request.companyId(), request.storeId(), request.operationId(), request.localCustomerId(),
                customer, 0L, "DNI", "12345678Z"), site.token()));
        var foreign = site();
        assertThatThrownBy(() -> service.reserve(request, foreign.token())).isInstanceOf(ResponseStatusException.class);
        service.reserve(request, site.token());
        var changed = new Reserve(request.companyId(), request.storeId(), request.operationId(), request.localCustomerId(),
                customer, 0L, "PASAPORTE", "OTHER");
        conflict(() -> service.reserve(changed, site.token()));
        assertThat(count("saas_customer_identity_link", site)).isZero();
    }

    @Test void receiverFailureRollsBackEventLinkAndOperationTogetherAndRetryRemainsPossible() {
        var site = site(); var request = reserve(site, master(site)); service.reserve(request, site.token());
        UUID eventId = UUID.randomUUID();
        jdbc.execute("alter table saas_customer_identity_link add constraint adoption_test_failure check (local_customer_id <> '" + request.localCustomerId() + "'::uuid)");
        try {
            assertThatThrownBy(() -> sync.receive(event(eventId, site, request), site.token())).isInstanceOf(DataIntegrityViolationException.class);
        } finally { jdbc.execute("alter table saas_customer_identity_link drop constraint adoption_test_failure"); }
        assertThat(events.findById(eventId)).isEmpty();
        assertThat(count("saas_customer_identity_link", site)).isZero();
        assertThat(jdbc.queryForObject("select status from saas_customer_adoption_operation where operation_id=?", String.class, request.operationId()))
                .isEqualTo("RESERVED");
        sync.receive(event(eventId, site, request), site.token());
        assertThat(count("saas_customer_identity_link", site)).isEqualTo(1);
    }

    @Test void masterProfileChangesAfterReservationAreNotOverwrittenOrRevisedByFinalization() {
        var site = site(); UUID customer = master(site); var request = reserve(site, customer);
        var selected = service.reserve(request, site.token());
        jdbc.update("update saas_erp_customer set name='Updated centrally' where id=?", customer);
        var changed = masterRow(customer);
        assertThat(service.reserve(request, site.token())).isEqualTo(selected);
        finish(site, request);
        assertThat(masterRow(customer)).isEqualTo(changed);
        var stale = identityChange(site, request, "NEXT");
        conflict(() -> identities.reserve(stale, site.token()));
    }

    @Test void twoInstallationsCannotReserveCompetingFiscalChangesForTheSameCentralRevision() {
        var first = site(); var second = site(first.company(), "002"); UUID customer = master(first);
        var a = reserve(first, customer); var b = reserve(second, customer);
        service.reserve(a, first.token()); finish(first, a);
        service.reserve(b, second.token()); finish(second, b);
        var firstEdit = identityChange(first, a, "FIRST");
        var secondEdit = identityChange(second, b, "SECOND");
        identities.reserve(firstEdit, first.token());
        conflict(() -> identities.reserve(secondEdit, second.token()));
        identities.cancel(firstEdit.operationId(), new CustomerIdentityOwnerRequest(a.companyId(), a.storeId(), a.localCustomerId()), first.token());
        assertThat(identities.reserve(secondEdit, second.token()).revision()).isEqualTo(1);
    }

    @Test void identityAndAdoptionCannotReserveTheSameLocalOwnerConcurrently() {
        var site = site(); UUID customer = master(site); var request = reserve(site, customer);
        service.reserve(request, site.token());
        var create = new CustomerIdentityReservationRequest(request.companyId(), request.storeId(), UUID.randomUUID(),
                request.localCustomerId(), null, null, "PASAPORTE", "OTHER", Map.of("clientId", "OTHER", "fiscalName", "Other", "address", Map.of()));
        conflict(() -> identities.reserve(create, site.token()));
        service.cancel(request.operationId(), owner(request), site.token());
        identities.reserve(create, site.token());
        conflict(() -> service.reserve(new Reserve(request.companyId(), request.storeId(), UUID.randomUUID(),
                request.localCustomerId(), request.customerId(), 0L, "NIF", "12345678Z"), site.token()));
    }

    @Test void doubleClickCannotReserveTwoLocalCopiesForOneInstallation() throws Exception {
        var site = site(); UUID customer = master(site);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> attempt(start, site, reserve(site, customer)));
            var b = pool.submit(() -> attempt(start, site, reserve(site, customer)));
            start.countDown();
            assertThat(java.util.List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "CUSTOMER_IDENTITY_CONFLICT");
        }
        assertThat(count("saas_customer_adoption_operation", site)).isEqualTo(1);
        assertThat(count("saas_customer_identity_link", site)).isZero();
    }

    private String attempt(CountDownLatch start, Site site, Reserve request) throws Exception {
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        try { service.reserve(request, site.token()); return "OK"; }
        catch (CustomerIdentityException exception) { return exception.getCode(); }
    }
    private CustomerIdentityReservationRequest identityChange(Site site, Reserve adoption, String number) {
        return new CustomerIdentityReservationRequest(site.company().getId(), site.store().getId(), UUID.randomUUID(),
                adoption.localCustomerId(), adoption.customerId(), 0L, "PASAPORTE", number,
                Map.of("clientId", "CENTRAL-001", "fiscalName", "Edited profile", "address", Map.of()));
    }
    private Map<String, Object> masterRow(UUID id) { return jdbc.queryForMap("select * from saas_erp_customer where id=?", id); }
    private UUID master(Site site) {
        return admin.createErpCustomer(site.company().getId(), new CreateErpCustomerRequest(
                "CENTRAL-001", "Central customer", "12345678Z", "test@example.invalid", "600000001", "NIF")).id();
    }
    private Reserve reserve(Site site, UUID customer) {
        return new Reserve(site.company().getId(), site.store().getId(), UUID.randomUUID(), UUID.randomUUID(), customer, 0L, "NIF", "12345678Z");
    }
    private static CustomerIdentityOwnerRequest owner(Reserve value) {
        return new CustomerIdentityOwnerRequest(value.companyId(), value.storeId(), value.localCustomerId());
    }
    private UUID finish(Site site, Reserve request) {
        UUID id = UUID.randomUUID(); sync.receive(event(id, site, request), site.token()); return id;
    }
    private SyncEventRequest event(UUID id, Site site, Reserve request) {
        return new SyncEventRequest(id, site.company().getId(), site.store().getId(), null, "CUSTOMER_ADOPTION",
                request.localCustomerId(), SyncOperation.ACTUALIZAR, Map.of("operationId", request.operationId().toString()));
    }
    private Site site() {
        var company = companies.saveAndFlush(new SaasCompany(UUID.randomUUID(), "Adoption test company",
                validCif("B" + COMPANY_NUMBER.getAndIncrement() + "0"), TaxpayerType.SOCIEDAD, TaxRegime.IVA, Instant.now()));
        return site(company, "001");
    }
    private Site site(SaasCompany company, String code) {
        var store = stores.saveAndFlush(new SaasStore(UUID.randomUUID(), company, code, "Test store", "Atlantic/Canary", Instant.now()));
        var license = licenses.saveAndFlush(new SaasLicense(UUID.randomUUID(), company, "ADOPTION-" + UUID.randomUUID(),
                Instant.now().plusSeconds(86400), 1, 1, Instant.now()));
        String token = tokens.newToken();
        var installation = installations.saveAndFlush(new SaasInstallation(UUID.randomUUID(), company, store,
                license, UUID.randomUUID(), "ADOPTION-TEST", null, tokens.hash(token), Instant.now()));
        return new Site(company, store, installation, token);
    }
    private int count(String table, Site site) {
        return jdbc.queryForObject("select count(*) from " + table + " where company_id=?", Integer.class, site.company().getId());
    }
    private static void conflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(CustomerIdentityException.class,
                error -> assertThat(error.getCode()).isEqualTo("CUSTOMER_IDENTITY_CONFLICT"));
    }
    private record Site(SaasCompany company, SaasStore store, SaasInstallation installation, String token) { }
}
