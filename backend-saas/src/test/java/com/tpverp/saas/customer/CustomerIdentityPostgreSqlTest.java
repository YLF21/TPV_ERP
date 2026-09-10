package com.tpverp.saas.customer;

import static com.tpverp.saas.SaasTestData.validCif;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.admin.AdminService;
import com.tpverp.saas.admin.CreateErpCustomerRequest;
import com.tpverp.saas.license.SaasCompany;
import com.tpverp.saas.license.SaasCompanyRepository;
import com.tpverp.saas.license.SaasInstallation;
import com.tpverp.saas.license.SaasInstallationRepository;
import com.tpverp.saas.license.SaasLicense;
import com.tpverp.saas.license.SaasLicenseRepository;
import com.tpverp.saas.license.SaasStore;
import com.tpverp.saas.license.SaasStoreRepository;
import com.tpverp.saas.license.TaxRegime;
import com.tpverp.saas.license.TaxpayerType;
import com.tpverp.saas.license.TokenHasher;
import com.tpverp.saas.master.MasterCsvService;
import com.tpverp.saas.sync.SaasSyncEvent;
import com.tpverp.saas.sync.SaasSyncEventRepository;
import com.tpverp.saas.sync.SyncEventRequest;
import com.tpverp.saas.sync.SyncEventService;
import com.tpverp.saas.sync.SyncOperation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
import org.flywaydb.core.Flyway;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** All writes use the isolated PostgreSQL test profile, never the developer business database. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerIdentityPostgreSqlTest {
    private static final AtomicInteger COMPANY_NUMBER = new AtomicInteger(9100000);
    @Autowired CustomerIdentityService service;
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
    @Autowired MasterCsvService csv;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void retryReturnsSameIdentityAndChangedRequestConflicts() {
        Site site = site();
        var request = request(site, "DNI", "12345678Z");
        var first = service.reserve(request, site.token());
        assertThat(service.reserve(request, site.token())).isEqualTo(first);
        assertThat(count("saas_erp_customer", site.company().getId())).isZero();
        assertThat(first.revision()).isEqualTo(1);
        var changed = new CustomerIdentityReservationRequest(request.companyId(), request.storeId(), request.operationId(),
                request.localCustomerId(), null, null, "PASAPORTE", "another", request.profile());
        assertCode(() -> service.reserve(changed, site.token()), "CUSTOMER_IDENTITY_CONFLICT");
    }

    @Test
    void concurrentStoresCanReserveOnlyOneOwnerIndependentOfType() throws Exception {
        Site first = site();
        Site second = site(first.company(), "002");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> concurrentReserve(ready, start, first, "DNI"));
            var b = executor.submit(() -> concurrentReserve(ready, start, second, "NIF"));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "CUSTOMER_DOCUMENT_DUPLICATE");
        }
        assertThat(count("saas_customer_document_claim", first.company().getId())).isEqualTo(1);
    }

    @Test
    void finalizationUsesAuthenticatedOutboxAndIsIdempotentAcrossEventIds() {
        Site site = site();
        var request = withProfile(request(site, "NIE", "X1234567L"), profile("C-001", "Cliente de prueba"));
        var response = service.reserve(request, site.token());
        UUID eventId = finalize(site, request, "C-001", "Cliente de prueba");
        finalize(site, request, "C-001", "Cliente de prueba");
        assertThat(events.findById(eventId).orElseThrow().getProjectionStatus())
                .isEqualTo(SaasSyncEvent.ProjectionStatus.PROJECTED);
        assertThat(count("saas_erp_customer", site.company().getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select customer_id from saas_customer_identity_link where local_customer_id = ?",
                UUID.class, request.localCustomerId())).isEqualTo(response.customerId());
        assertThat(jdbc.queryForObject("select tax_id from saas_erp_customer where id = ?", String.class,
                response.customerId())).isEqualTo("X1234567L");
        assertCode(() -> finalize(site, request, "C-001", "Changed after commit"), "CUSTOMER_IDENTITY_CONFLICT");
        assertThat(jdbc.queryForObject("select name from saas_erp_customer where id = ?", String.class,
                response.customerId())).isEqualTo("Cliente de prueba");
    }

    @Test
    void renameKeepsOldNumberUntilCommittedAndRejectsStaleRevision() {
        Site site = site();
        var create = request(site, "DNI", "12345678Z");
        var first = service.reserve(create, site.token());
        finalize(site, create, "C-001", "Cliente");
        var rename = rename(create, first, "NIF", "B12345674");
        var changed = service.reserve(rename, site.token());
        assertThat(count("saas_customer_document_claim", site.company().getId())).isEqualTo(2);
        Site other = site(site.company(), "002");
        assertCode(() -> service.reserve(request(other, "DNI", "12345678Z"), other.token()), "CUSTOMER_DOCUMENT_DUPLICATE");
        assertCode(() -> service.reserve(request(other, "NIF", "B12345674"), other.token()), "CUSTOMER_DOCUMENT_DUPLICATE");
        assertCode(() -> service.reserve(rename(create, first, "PASAPORTE", "different"), site.token()), "CUSTOMER_IDENTITY_CONFLICT");
        assertThat(jdbc.queryForObject("select tax_id from saas_erp_customer where id = ?", String.class,
                first.customerId())).isEqualTo("12345678Z");
        finalize(site, rename, "C-001", "Cliente actualizado");
        assertThat(count("saas_customer_document_claim", site.company().getId())).isEqualTo(1);
        assertThat(changed.revision()).isEqualTo(2);
        assertCode(() -> service.reserve(rename(create, first, "PASAPORTE", "stale"), site.token()), "CUSTOMER_IDENTITY_CONFLICT");
        assertThat(service.reserve(request(other, "DNI", "12345678Z"), other.token())).isNotNull();
    }

    @Test
    void cancelKeepsOldClaimAndCannotReleaseCommittedRegistration() {
        Site site = site();
        var create = request(site, "DNI", "12345678Z");
        var first = service.reserve(create, site.token());
        finalize(site, create, "C-001", "Cliente");
        var rename = rename(create, first, "NIF", "B12345674");
        service.reserve(rename, site.token());
        var owner = new CustomerIdentityOwnerRequest(create.companyId(), create.storeId(), create.localCustomerId());
        service.cancel(rename.operationId(), owner, site.token());
        service.cancel(rename.operationId(), owner, site.token());
        assertThat(count("saas_customer_document_claim", site.company().getId())).isEqualTo(1);
        assertCode(() -> service.reserve(rename, site.token()), "CUSTOMER_IDENTITY_CONFLICT");
        assertCode(() -> service.cancel(create.operationId(), owner, site.token()), "CUSTOMER_IDENTITY_CONFLICT");
        assertThat(jdbc.queryForObject("select document_number from saas_customer_document_claim where company_id = ?",
                String.class, create.companyId())).isEqualTo("12345678Z");
    }

    @Test
    void cancellationBeforeDelayedReservePersistsTombstone() {
        Site site = site();
        var request = request(site, "PASAPORTE", "some value");
        var owner = new CustomerIdentityOwnerRequest(request.companyId(), request.storeId(), request.localCustomerId());
        service.cancel(request.operationId(), owner, site.token());
        service.cancel(request.operationId(), owner, site.token());
        assertCode(() -> service.reserve(request, site.token()), "CUSTOMER_IDENTITY_CONFLICT");
        assertCode(() -> finalize(site, request, "C-001", "Cliente"), "CUSTOMER_IDENTITY_CONFLICT");
        assertThat(count("saas_customer_document_claim", request.companyId())).isZero();
    }

    @Test
    void unlinkedLegacyMasterCannotBeClaimedByItsTaxNumber() {
        Site site = site();
        admin.createErpCustomer(site.company().getId(), new CreateErpCustomerRequest("OLD", "Legacy", "B12345674", null, null));
        assertCode(() -> service.reserve(request(site, "NIF", "B12345674"), site.token()), "CUSTOMER_DOCUMENT_DUPLICATE");
        assertThat(count("saas_customer_identity_link", site.company().getId())).isZero();
    }

    @Test
    void adminCsvAndDirectMasterSqlCannotBypassPendingReservation() {
        Site site = site();
        var reserved = request(site, "NIF", "B12345674");
        service.reserve(reserved, site.token());
        assertThatThrownBy(() -> admin.createErpCustomer(site.company().getId(),
                new CreateErpCustomerRequest("ADMIN", "Duplicate", "b-12345674", null, null)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("CUSTOMER_DOCUMENT_DUPLICATE");
        assertThatThrownBy(() -> csv.importCsv(site.company().getId(), "customers",
                "code,name,tax_id,email,phone\nCSV,Duplicate,B12345674,,\n"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("CUSTOMER_DOCUMENT_DUPLICATE");
        assertThatThrownBy(() -> jdbc.update("""
                insert into saas_erp_customer(id, company_id, code, name, tax_id, active, created_at)
                values (?, ?, 'RAW', 'Duplicate', 'b-12345674', true, now())
                """, UUID.randomUUID(), site.company().getId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("CUSTOMER_DOCUMENT_DUPLICATE");
        assertThat(count("saas_erp_customer", site.company().getId())).isZero();
    }

    @Test
    void masterRenameCannotBypassPendingOperationAndCsvRespectsChecksum() {
        Site site = site();
        var create = request(site, "DNI", "12345678Z");
        var first = service.reserve(create, site.token());
        finalize(site, create, "C-001", "Cliente");
        service.reserve(rename(create, first, "NIF", "B12345674"), site.token());
        assertThatThrownBy(() -> jdbc.update("update saas_erp_customer set tax_id = 'ABC' where id = ?", first.customerId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("CUSTOMER_IDENTITY_CONFLICT");
        assertCode(() -> csv.importCsv(site.company().getId(), "customers",
                "code,name,tax_id,email,phone,document_type\nC-001,Cliente,12345678A,,,DNI\n"), "CUSTOMER_DOCUMENT_INVALID");
    }

    @Test
    void failureDuringFinalizationRollsBackOperationAndLeavesReservationRetryable() {
        Site site = site();
        admin.createErpCustomer(site.company().getId(), new CreateErpCustomerRequest("TAKEN", "Another", "B12345674", null, null));
        var request = withProfile(request(site, "DNI", "12345678Z"), profile("AVAILABLE", "Cliente"));
        var reserved = service.reserve(request, site.token());
        jdbc.execute("alter table saas_erp_customer add constraint identity_test_forced_failure check (id <> '"
                + reserved.customerId() + "'::uuid)");
        try {
            assertThatThrownBy(() -> finalize(site, request, "AVAILABLE", "Cliente"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("alter table saas_erp_customer drop constraint identity_test_forced_failure");
        }
        assertThat(jdbc.queryForObject("select status from saas_customer_identity_operation where operation_id = ?",
                String.class, request.operationId())).isEqualTo("RESERVED");
        finalize(site, request, "AVAILABLE", "Cliente");
        assertThat(count("saas_erp_customer", site.company().getId())).isEqualTo(2);
    }

    @Test
    void authenticationAndOwnerMismatchCannotReadOrFinalizeAnotherIdentity() throws Exception {
        Site site = site();
        Site other = site(site.company(), "002");
        Site foreign = site();
        var request = request(site, "DNI", "12345678Z");
        service.reserve(request, site.token());
        assertThatThrownBy(() -> service.reserve(request, other.token()))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(401));
        assertThatThrownBy(() -> service.reserve(request, foreign.token())).isInstanceOf(ResponseStatusException.class);
        assertCode(() -> service.cancel(request.operationId(), new CustomerIdentityOwnerRequest(other.company().getId(),
                other.store().getId(), request.localCustomerId()), other.token()), "CUSTOMER_IDENTITY_CONFLICT");
        var stolen = new SyncEventRequest(UUID.randomUUID(), other.company().getId(), other.store().getId(), null,
                "CUSTOMER_IDENTITY", request.localCustomerId(), SyncOperation.ACTUALIZAR,
                Map.of("operationId", request.operationId().toString(), "clientId", "STOLEN", "fiscalName", "Never saved"));
        assertCode(() -> sync.receive(stolen, other.token()), "CUSTOMER_IDENTITY_CONFLICT");
        mvc.perform(post("/api/v1/customer-identities/reservations")
                .header("X-TPV-Installation-Token", site.token()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(request(site, "DNI", "12345678A"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CUSTOMER_DOCUMENT_INVALID"));
        assertThat(count("saas_erp_customer", site.company().getId())).isZero();
    }

    @Test
    void sameNumberIsAllowedInDifferentCompaniesAndPassportHasNoChecksum() {
        Site first = site();
        Site other = site();
        var a = service.reserve(request(first, "PASAPORTE", " arbitrary - ID "), first.token());
        var b = service.reserve(request(other, "PASAPORTE", "ARBITRARYID"), other.token());
        assertThat(a.documentNumber()).isEqualTo("ARBITRARYID");
        assertThat(b.customerId()).isNotEqualTo(a.customerId());
    }

    @Test
    void cancellingInFlightReserveCannotLeaveAClaimOrReviveTheOperation() throws Exception {
        Site site = site();
        var request = request(site, "DNI", "12345678Z");
        var owner = new CustomerIdentityOwnerRequest(request.companyId(), request.storeId(), request.localCustomerId());
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var reserve = executor.submit(() -> {
                ready.countDown(); start.await(10, TimeUnit.SECONDS);
                try { service.reserve(request, site.token()); }
                catch (CustomerIdentityException error) { assertThat(error.getCode()).isEqualTo("CUSTOMER_IDENTITY_CONFLICT"); }
                return true;
            });
            var cancel = executor.submit(() -> {
                ready.countDown(); start.await(10, TimeUnit.SECONDS);
                service.cancel(request.operationId(), owner, site.token());
                return true;
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(reserve.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(cancel.get(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count("saas_customer_document_claim", site.company().getId())).isZero();
        assertCode(() -> service.reserve(request, site.token()), "CUSTOMER_IDENTITY_CONFLICT");
    }

    @Test
    void directAdministrativeInsertAndReservationSerializeTheirSharedNamespace() throws Exception {
        Site site = site();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var reserve = executor.submit(() -> concurrentReserve(ready, start, site, "DNI"));
            var master = executor.submit(() -> {
                ready.countDown(); start.await(10, TimeUnit.SECONDS);
                try {
                    admin.createErpCustomer(site.company().getId(),
                            new CreateErpCustomerRequest("ADMIN", "Customer", "12345678Z", null, null));
                    return "OK";
                } catch (DataIntegrityViolationException error) {
                    assertThat(error.getMessage()).contains("CUSTOMER_DOCUMENT_DUPLICATE");
                    return "CUSTOMER_DOCUMENT_DUPLICATE";
                }
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(reserve.get(20, TimeUnit.SECONDS), master.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "CUSTOMER_DOCUMENT_DUPLICATE");
        }
        assertThat(count("saas_customer_document_claim", site.company().getId())).isEqualTo(1);
    }

    @Test
    void migrationStopsOnLegacyNormalizedDuplicatesWithoutMergingThem() {
        String schema = "customer_identity_legacy_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("create schema " + schema);
        try {
            Flyway.configure().configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                    .dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema)
                    .target("52").load().migrate();
            UUID companyId = UUID.randomUUID();
            jdbc.update("insert into " + schema + ".saas_company(id, name, tax_id, taxpayer_type, tax_regime, created_at)"
                    + " values (?, 'Legacy fixture', 'B12345674', 'SOCIEDAD', 'IVA', now())", companyId);
            // This schema is explicitly pre-V53 and has the legacy non-unique tax identifiers.
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.execute("set local search_path to " + schema);
                for (String code : List.of("A", "B")) {
                    jdbc.update("insert into saas_erp_customer(id, company_id, code, name, tax_id, active, created_at)"
                            + " values (?, ?, ?, 'Legacy customer', ?, true, now())", UUID.randomUUID(), companyId,
                            code, code.equals("A") ? "B12345674" : "b-1234 5674");
                }
            });
            assertThatThrownBy(() -> Flyway.configure().configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                    .dataSource(jdbc.getDataSource()).schemas(schema)
                    .defaultSchema(schema).load().migrate()).hasStackTraceContaining("CUSTOMER_DOCUMENT_DUPLICATE");
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".saas_erp_customer", Integer.class)).isEqualTo(2);
        } finally {
            jdbc.execute("drop schema " + schema + " cascade");
        }
    }

    @Test
    void reservedCreatesHoldCapacityAgainstFurtherReservationsAndMasterWriters() {
        Site site = site();
        String plan = ("IDENTITY-" + UUID.randomUUID()).toUpperCase(java.util.Locale.ROOT);
        jdbc.update("""
                insert into saas_plan_policy(plan_name, max_tenant_users, max_stores, max_licenses,
                  max_master_records, max_sync_events_per_day) values (?, 10, 10, 10, 1, 10000)
                """, plan);
        jdbc.update("""
                insert into saas_company_operations(company_id, plan_name, billing_status, support_status, updated_at)
                values (?, ?, 'ACTIVE', 'ACTIVE', now())
                """, site.company().getId(), plan);
        var request = request(site, "DNI", "12345678Z");
        service.reserve(request, site.token());
        assertCode(() -> service.reserve(request(site, "NIF", "B12345674"), site.token()), "CUSTOMER_IDENTITY_CONFLICT");
        assertThatThrownBy(() -> admin.createErpCustomer(site.company().getId(),
                new CreateErpCustomerRequest("ADMIN", "Another", "B12345674", null, null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> csv.importCsv(site.company().getId(), "customers",
                "code,name,tax_id,email,phone\nCSV,Another,B12345674,,\n")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into saas_erp_customer(id, company_id, code, name, tax_id, active, created_at)
                values (?, ?, 'RAW', 'Another', 'B12345674', true, now())
                """, UUID.randomUUID(), site.company().getId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        finalize(site, request, "C-001", "Cliente");
        assertThat(count("saas_erp_customer", site.company().getId())).isEqualTo(1);
    }

    @Test
    void acceptsLocalProfileLengthsAndSqlKeyMatchesJavaAsciiTrim() {
        Site site = site();
        String fiscalName = "客".repeat(255);
        String phone = "1".repeat(64);
        String email = "a".repeat(308) + "@example.com";
        var request = withProfile(request(site, "PASAPORTE", "A".repeat(64)),
                Map.of("clientId", "LONG", "fiscalName", fiscalName, "phone", phone, "email", email));
        var reserved = service.reserve(request, site.token());
        sync.receive(new SyncEventRequest(UUID.randomUUID(), site.company().getId(), site.store().getId(), null,
                "CUSTOMER_IDENTITY", request.localCustomerId(), SyncOperation.ACTUALIZAR,
                Map.of("operationId", request.operationId().toString(), "clientId", "LONG", "fiscalName", fiscalName,
                        "phone", phone, "email", email)), site.token());
        Map<String, Object> persisted = jdbc.queryForMap("select name, tax_id, phone, email from saas_erp_customer where id = ?",
                reserved.customerId());
        assertThat(persisted).containsEntry("name", fiscalName).containsEntry("tax_id", "A".repeat(64))
                .containsEntry("phone", phone).containsEntry("email", email);
        String boundary = java.util.stream.IntStream.rangeClosed(1, 32)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        String formatted = boundary + "ab-c 1\t2" + boundary;
        assertThat(jdbc.queryForObject("select saas_customer_document_key(?)", String.class, formatted))
                .isEqualTo(CustomerDocumentIdentity.normalize(formatted)).isEqualTo("ABC1\t2");
    }

    @Test
    void reservesCodeBeforeLocalCommitAndRejectsChangedPreparedProfile() {
        Site site = site();
        var request = request(site, "DNI", "12345678Z");
        service.reserve(request, site.token());
        assertThatThrownBy(() -> admin.createErpCustomer(site.company().getId(),
                new CreateErpCustomerRequest("C-001", "Competing code", "B12345674", null, null)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("CUSTOMER_IDENTITY_CONFLICT");
        assertThatThrownBy(() -> csv.importCsv(site.company().getId(), "customers",
                "code,name,tax_id,email,phone\nC-001,Competing code,B12345674,,\n"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("CUSTOMER_IDENTITY_CONFLICT");
        assertCode(() -> service.reserve(withProfile(request, profile("CHANGED", "Cliente")), site.token()),
                "CUSTOMER_IDENTITY_CONFLICT");
        assertCode(() -> finalize(site, request, "C-001", "Not the prepared profile"), "CUSTOMER_IDENTITY_CONFLICT");
        assertThat(count("saas_erp_customer", site.company().getId())).isZero();
        finalize(site, request, "C-001", "Cliente");
    }

    @Test
    void existingCentralCodeCollisionIsRejectedDuringReserveNotDuringFinalize() {
        Site site = site();
        admin.createErpCustomer(site.company().getId(), new CreateErpCustomerRequest("C-001", "Other", "B12345674", null, null));
        var request = request(site, "DNI", "12345678Z");
        assertCode(() -> service.reserve(request, site.token()), "CUSTOMER_IDENTITY_CONFLICT");
        assertThat(count("saas_customer_identity_operation", site.company().getId())).isZero();
        assertThat(count("saas_customer_document_claim", site.company().getId())).isEqualTo(1);
    }

    @Test
    void centralCsvProfileEditMakesOldLocalRevisionStaleBeforeAnyNewReservation() {
        Site site = site();
        var create = request(site, "DNI", "12345678Z");
        var original = service.reserve(create, site.token());
        finalize(site, create, "C-001", "Cliente");
        csv.importCsv(site.company().getId(), "customers",
                "code,name,tax_id,email,phone,document_type\nC-001,Perfil modificado en SaaS,12345678Z,,,DNI\n");
        assertThat(jdbc.queryForObject("select identity_revision from saas_erp_customer where id = ?", Long.class,
                original.customerId())).isEqualTo(2L);
        assertCode(() -> service.reserve(rename(create, original, "NIF", "B12345674"), site.token()),
                "CUSTOMER_IDENTITY_CONFLICT");
        assertThat(jdbc.queryForObject("select name from saas_erp_customer where id = ?", String.class,
                original.customerId())).isEqualTo("Perfil modificado en SaaS");
        assertThat(jdbc.queryForObject("select count(*) from saas_customer_identity_operation where company_id = ? and status = 'RESERVED'",
                Integer.class, site.company().getId())).isZero();
        assertThat(count("saas_customer_document_claim", site.company().getId())).isEqualTo(1);
    }

    @Test
    void httpReservationAndCancellationEnforceInstallationScopeAndReturnStableErrors() throws Exception {
        Site site = site();
        Site otherStore = site(site.company(), "002");
        Site foreignCompany = site();
        var request = request(site, "DNI", "12345678Z");
        String endpoint = "/api/v1/customer-identities/reservations";
        byte[] body = mapper.writeValueAsBytes(request);
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        for (String wrongToken : List.of("invalid-token", otherStore.token(), foreignCompany.token())) {
            mvc.perform(post(endpoint).header("X-TPV-Installation-Token", wrongToken)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        }
        var result = mvc.perform(post(endpoint).header("X-TPV-Installation-Token", site.token())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operationId").value(request.operationId().toString()))
                .andExpect(jsonPath("$.documentNumber").value("12345678Z"))
                .andExpect(jsonPath("$.documentType").value("DNI"))
                .andExpect(jsonPath("$.revision").value(1)).andReturn();
        String customerId = mapper.readTree(result.getResponse().getContentAsByteArray()).get("customerId").asText();
        mvc.perform(post(endpoint).header("X-TPV-Installation-Token", site.token())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customerId").value(customerId));
        mvc.perform(post(endpoint).header("X-TPV-Installation-Token", otherStore.token())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(request(otherStore, "NIF", "12345678Z"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CUSTOMER_DOCUMENT_DUPLICATE"));
        mvc.perform(post(endpoint).header("X-TPV-Installation-Token", site.token())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(request(site, "NIF", "B12345674"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CUSTOMER_IDENTITY_CONFLICT"));
        String cancelEndpoint = endpoint + "/" + request.operationId() + "/cancel";
        var owner = new CustomerIdentityOwnerRequest(request.companyId(), request.storeId(), request.localCustomerId());
        mvc.perform(post(cancelEndpoint).header("X-TPV-Installation-Token", "invalid-token")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(owner)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(cancelEndpoint).header("X-TPV-Installation-Token", otherStore.token())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(new CustomerIdentityOwnerRequest(
                        request.companyId(), otherStore.store().getId(), request.localCustomerId()))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CUSTOMER_IDENTITY_CONFLICT"));
        mvc.perform(post(cancelEndpoint).header("X-TPV-Installation-Token", site.token())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(owner)))
                .andExpect(status().isNoContent());
        assertThat(count("saas_customer_document_claim", site.company().getId())).isZero();
        assertThat(count("saas_customer_code_claim", site.company().getId())).isZero();
    }

    private String concurrentReserve(CountDownLatch ready, CountDownLatch start, Site site, String type) throws Exception {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        try { service.reserve(request(site, type, "12345678Z"), site.token()); return "OK"; }
        catch (CustomerIdentityException error) { return error.getCode(); }
    }

    private UUID finalize(Site site, CustomerIdentityReservationRequest request, String code, String name) {
        UUID eventId = UUID.randomUUID();
        sync.receive(new SyncEventRequest(eventId, site.company().getId(), site.store().getId(), null,
                "CUSTOMER_IDENTITY", request.localCustomerId(), SyncOperation.ACTUALIZAR,
                Map.of("operationId", request.operationId().toString(), "clientId", code, "fiscalName", name,
                        "address", Map.of("street", "Calle de prueba"))), site.token());
        return eventId;
    }

    private static CustomerIdentityReservationRequest request(Site site, String type, String document) {
        return new CustomerIdentityReservationRequest(site.company().getId(), site.store().getId(), UUID.randomUUID(),
                UUID.randomUUID(), null, null, type, document, profile("C-" + site.store().getCode(), "Cliente"));
    }

    private static CustomerIdentityReservationRequest rename(CustomerIdentityReservationRequest create,
            CustomerIdentityReservationResponse current, String type, String document) {
        return new CustomerIdentityReservationRequest(create.companyId(), create.storeId(), UUID.randomUUID(),
                create.localCustomerId(), current.customerId(), current.revision(), type, document,
                profile(create.profile().get("clientId").toString(), "Cliente actualizado"));
    }

    private static Map<String, Object> profile(String code, String name) {
        return Map.of("clientId", code, "fiscalName", name, "address", Map.of("street", "Calle de prueba"));
    }

    private static CustomerIdentityReservationRequest withProfile(CustomerIdentityReservationRequest request, Map<String, Object> profile) {
        return new CustomerIdentityReservationRequest(request.companyId(), request.storeId(), request.operationId(),
                request.localCustomerId(), request.expectedCustomerId(), request.expectedRevision(), request.documentType(),
                request.documentNumber(), profile);
    }

    private Site site() {
        SaasCompany company = companies.saveAndFlush(new SaasCompany(UUID.randomUUID(), "Identity test company",
                validCif("B" + COMPANY_NUMBER.getAndIncrement() + "0"), TaxpayerType.SOCIEDAD,
                TaxRegime.IVA, Instant.now()));
        return site(company, "001");
    }

    private Site site(SaasCompany company, String code) {
        SaasStore store = stores.saveAndFlush(new SaasStore(UUID.randomUUID(), company, code, "Test store",
                "Atlantic/Canary", Instant.now()));
        SaasLicense license = licenses.saveAndFlush(new SaasLicense(UUID.randomUUID(), company,
                "IDENTITY-TEST-" + UUID.randomUUID(), Instant.now().plusSeconds(86400), 1, 1, Instant.now()));
        String token = tokens.newToken();
        SaasInstallation installation = installations.saveAndFlush(new SaasInstallation(UUID.randomUUID(), company,
                store, license, UUID.randomUUID(), "IDENTITY-TEST", null, tokens.hash(token), Instant.now()));
        return new Site(company, store, installation, token);
    }

    private int count(String table, UUID companyId) {
        return jdbc.queryForObject("select count(*) from " + table + " where company_id = ?", Integer.class, companyId);
    }

    private static void assertCode(ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(CustomerIdentityException.class,
                error -> assertThat(error.getCode()).isEqualTo(code));
    }

    private record Site(SaasCompany company, SaasStore store, SaasInstallation installation, String token) {}
}
