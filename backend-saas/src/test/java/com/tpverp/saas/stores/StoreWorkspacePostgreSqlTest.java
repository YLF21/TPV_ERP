package com.tpverp.saas.stores;

import static com.tpverp.saas.SaasTestData.*;
import static com.tpverp.saas.stores.StoreWorkspaceApi.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.admin.RenewLicenseRequest;
import com.tpverp.saas.license.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.flyway.default-schema=store_workspace_test",
        "spring.datasource.hikari.schema=store_workspace_test",
        "spring.jpa.properties.hibernate.default_schema=store_workspace_test"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class StoreWorkspacePostgreSqlTest {
    private static final BigDecimal PRICE = new BigDecimal("29.90");
    private static final Instant EXPIRY = Instant.parse("2099-01-01T00:00:00Z");
    private static final AtomicInteger COMPANY_NUMBER = new AtomicInteger(9600000);
    @Autowired StoreAdministrationService stores;
    @Autowired LicenseWorkspaceService licenses;
    @Autowired SaasCompanyRepository companies;
    @Autowired SaasStoreRepository storeRepository;
    @Autowired SaasLicenseRepository licenseRepository;
    @Autowired SaasPairingCodeRepository pairingRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired LicenseLinkService linking;
    @Autowired com.tpverp.saas.admin.AdminService admin;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void isolatedSchema() {
        assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo("store_workspace_test");
    }

    @Test
    void centralCodesAreGlobalAndFiscalIdentityAndUuidRemainUnchanged() {
        var first = createStore(company(), "001");
        var second = createStore(company(), "001");
        assertThat(first.internalCode()).matches("35[0-9]{5}").isNotEqualTo(second.internalCode());
        assertThat(first.code()).isEqualTo("001");
        assertThat(first.active()).isTrue();
        assertThat(first.lastSyncAt()).isNull();
        assertThat(stores.list(first.companyId(), first.internalCode(), true, 0, 10).items()).extracting(StoreRow::id).containsExactly(first.id());
        assertThat(stores.list(first.companyId(), "no such store", null, 0, 10).total()).isZero();
        assertThatThrownBy(() -> createStore(companies.findById(first.companyId()).orElseThrow(), "001"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void concurrentProvisioningNeverAllocatesDuplicateCodes() throws Exception {
        var fixtures = java.util.stream.IntStream.range(0, 12).mapToObj(ignored -> company()).toList();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(6)) {
            var futures = fixtures.stream().map(company -> executor.submit(() -> {
                start.await(); return createStore(company, "001").internalCode();
            })).toList();
            start.countDown();
            var codes = new HashSet<String>();
            for (var future : futures) assertThat(codes.add(future.get(30, TimeUnit.SECONDS))).isTrue();
            assertThat(codes).hasSize(12).allMatch(code -> code.matches("35[0-9]{5}"));
        }
    }

    @Test
    void assignedCodeIsPermanentAcrossAddressAndActivityChanges() {
        var created = createStore(company(), "001");
        var address = new LinkedHashMap<>(fiscalAddress()); address.put("codigoPostal", "28001");
        var updated = stores.update(created.id(), new UpdateStore("Changed", address, "Europe/Madrid", false, TaxRegime.IVA, PRICE, StoreBillingPeriod.MONTHLY, 2, 1, EXPIRY));
        assertThat(updated.id()).isEqualTo(created.id());
        assertThat(updated.internalCode()).isEqualTo(created.internalCode());
        assertThat(updated.code()).isEqualTo("001");
        assertThat(updated.active()).isFalse();
        assertThatThrownBy(() -> stores.assignCode(created.id(), new AssignCode("2800001", "Cambiar codigo")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> jdbc.update("update saas_store set internal_code=null where id=?", created.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(stores.list(created.companyId(), "", true, 0, 25).items()).isEmpty();
    }

    @Test
    void overseasAndMissingLegacyAddressesStayUnassignedAndPrefixNineIsReserved() {
        var owner = company();
        var foreign = new LinkedHashMap<>(fiscalAddress()); foreign.put("pais", "FR"); foreign.put("codigoPostal", "75001");
        var legacy = storeRepository.saveAndFlush(new SaasStore(UUID.randomUUID(), owner, "001", "Legacy", foreign, "Europe/Paris", Instant.now()));
        var missing = storeRepository.saveAndFlush(new SaasStore(UUID.randomUUID(), owner, "002", "Missing", null, "Europe/Madrid", Instant.now()));
        assertThat(stores.list(owner.getId(), "", null, 0, 25).items()).allMatch(row -> row.internalCode() == null);
        assertThat(stores.updateActivity(missing.getId(), false).active()).isFalse();
        assertThat(stores.updateActivity(missing.getId(), true).internalCode()).isNull();
        assertThatThrownBy(() -> stores.create(owner.getId(), new CreateStore("003", "Overseas", foreign, "Europe/Paris", TaxRegime.IVA, PRICE, StoreBillingPeriod.MONTHLY, 2, 1, EXPIRY)))
                .isInstanceOf(ResponseStatusException.class);
        var reserved = new LinkedHashMap<>(fiscalAddress()); reserved.put("codigoPostal", "90001");
        assertThatThrownBy(() -> stores.create(owner.getId(), new CreateStore("003", "Reserved", reserved, "Europe/Madrid", TaxRegime.IVA, PRICE, StoreBillingPeriod.MONTHLY, 2, 1, EXPIRY)))
                .isInstanceOf(ResponseStatusException.class);
        var repaired = stores.update(missing.getId(), new UpdateStore("Repaired", fiscalAddress(), "Atlantic/Canary", true, TaxRegime.IVA, PRICE, StoreBillingPeriod.MONTHLY, 2, 1, EXPIRY));
        assertThat(repaired.internalCode()).matches("35[0-9]{5}");
        assertThat(stores.list(owner.getId(), legacy.getName(), null, 0, 25).items().getFirst().internalCode()).isNull();
    }

    @Test
    void licenseCreationDerivesCompanyFromStoreAndReusesOnlyWithPairingPermission() {
        var company = company(); var store = createStore(company, "001");
        var request = licenseRequest(company.getId(), store.id());
        var created = licenses.create(request);
        assertThat(created.storeId()).isEqualTo(store.id());
        assertThat(created.pairingCode()).startsWith("TPV-");
        assertThat(Duration.between(created.serverNow(), created.pairingExpiresAt())).isEqualTo(Duration.ofMinutes(30));
        assertThat(jdbc.queryForObject("select store_id from saas_license where id=?", UUID.class, created.id())).isEqualTo(store.id());
        assertThat(jdbc.queryForObject("select store_id from saas_pairing_code where license_id=?", UUID.class, created.id())).isEqualTo(store.id());
        assertThatThrownBy(() -> licenses.create(request)).isInstanceOf(ResponseStatusException.class);
        var foreign = createStore(company(), "001");
        assertThat(licenses.create(licenseRequest(company.getId(), foreign.id())).companyId()).isEqualTo(foreign.companyId());
        var reused = licenses.create(request, true);
        assertThat(reused.id()).isEqualTo(created.id());
        assertThat(reused.pairingCode()).isNotEqualTo(created.pairingCode());
        assertThat(Duration.between(reused.serverNow(), reused.pairingExpiresAt())).isEqualTo(Duration.ofMinutes(30));
        assertThatThrownBy(() -> licenses.create(new CreateLicense(UUID.randomUUID()))).isInstanceOf(ResponseStatusException.class);
        stores.update(store.id(), new UpdateStore(store.name(), fiscalAddress(), "Atlantic/Canary", false, TaxRegime.IVA, PRICE, StoreBillingPeriod.MONTHLY, 2, 1, EXPIRY));
        assertThatThrownBy(() -> licenses.create(licenseRequest(company.getId(), store.id()))).isInstanceOf(ResponseStatusException.class);
        assertThat(licenses.list(company.getId(), "", null, null, null, null, 0, 25).total()).isEqualTo(1);
    }

    @Test
    void licensePagesFilterCodeStateExpiryConnectionsAndExposeCompanyDebtPerCurrency() {
        var company = company(); var store = createStore(company, "001");
        var first = licenses.create(licenseRequest(company.getId(), store.id()));
        var secondStore = createStore(company, "002");
        var second = licenses.create(licenseRequest(company.getId(), secondStore.id()));
        UUID euroInvoice = invoice(company.getId(), "EUR", "100.25"); invoice(company.getId(), "USD", "20.00");
        jdbc.update("""
                insert into saas_billing_payment(id,invoice_id,amount,method,reference,paid_at,created_at)
                values (?,?,'50.00','TRANSFERENCIA','TEST',now(),now())
                """, UUID.randomUUID(), euroInvoice);
        var page = licenses.list(company.getId(), "", "VALIDA", Instant.parse("2100-01-01T00:00:00Z"), false, null, 0, 1);
        assertThat(page.total()).isEqualTo(2); assertThat(page.totalPages()).isEqualTo(2); assertThat(page.items()).hasSize(1);
        var row = page.items().getFirst();
        assertThat(row.stores()).extracting(StoreLink::id).containsExactly(store.id());
        assertThat(licenses.list(company.getId(), store.internalCode(), null, null, null, null, 0, 25).items())
                .extracting(LicenseRow::id).containsExactly(first.id());
        assertThat(row.billingScope()).isEqualTo("COMPANY"); assertThat(row.companyBillingStatus()).isNull();
        assertThat(row.companyDebt()).extracting(CompanyDebt::currency).containsExactly("EUR", "USD");
        assertThat(row.companyDebt().getFirst().outstanding()).isEqualByComparingTo("50.25");
        assertThat(row.companyDebt().getFirst().overdue()).isEqualByComparingTo("50.25");
        assertThat(row.lastSyncAt()).isNull();
        assertThat(licenses.list(company.getId(), "", null, null, true, null, 0, 25).items()).isEmpty();
        jdbc.update("update saas_license set valid_until='2000-01-01T00:00:00Z' where id=?", second.id());
        assertThat(licenses.list(company.getId(), "", "CADUCADA", null, null, null, 0, 25).items()).extracting(LicenseRow::id).containsExactly(second.id());
        assertThat(licenses.list(company.getId(), "", "VALIDA", null, null, null, 0, 25).items()).extracting(LicenseRow::id).containsExactly(first.id());
    }

    @Test
    void historicalMultiStoreLicensesKeepAllLinkedStoresWithoutInventingOne() {
        var company = company(); var one = createStore(company, "001"); var two = createStore(company, "002");
        var license = licenseRepository.saveAndFlush(new SaasLicense(UUID.randomUUID(), company, "LEGACY-" + UUID.randomUUID(), Instant.parse("2099-01-01T00:00:00Z"), 2, 0, Instant.now()));
        for (var id : List.of(one.id(), two.id())) {
            pairingRepository.saveAndFlush(new SaasPairingCode(UUID.randomUUID(), company, storeRepository.findById(id).orElseThrow(), license,
                    "PAIR-" + UUID.randomUUID(), Instant.parse("2099-01-01T00:00:00Z"), Instant.now()));
        }
        var row = licenses.list(company.getId(), "", null, null, null, null, 0, 25).items().getFirst();
        assertThat(row.stores()).extracting(StoreLink::id).containsExactly(one.id(), two.id());
        assertThat(jdbc.queryForObject("select store_id from saas_license where id=?", UUID.class, license.getId())).isNull();
    }

    @Test
    void activationCodeRecoveryRequiresCreatePermissionAndPreservesCodesAcrossReads() throws Exception {
        var company = company();
        var store = createStore(company, "001");
        String creator = adminWithPermissions("ADD_COMPANY");
        String regenerateOnly = adminWithPermissions("REGENERATE_PAIRING_CODE");
        String endpoint = "/api/v1/admin/license-workspace/activation-codes";
        mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
        for (String user : List.of("viewer", regenerateOnly, adminWithPermissions())) {
            mvc.perform(get(endpoint).header("Authorization", basic(user))).andExpect(status().isForbidden());
        }
        String response = mvc.perform(post("/api/v1/admin/license-workspace").header("Authorization", basic(creator))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(new CreateLicense(store.id()))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        var created = mapper.readValue(response, CreatedLicense.class);
        assertThat(Duration.between(created.serverNow(), created.pairingExpiresAt())).isEqualTo(Duration.ofMinutes(30));
        ActivationCodeRow original = null;
        for (int read = 0; read < 2; read++) {
            String recovered = mvc.perform(get(endpoint).header("Authorization", basic(creator))
                            .param("companyId", company.getId().toString()))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(25))
                    .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.totalPages").value(1))
                    .andReturn().getResponse().getContentAsString();
            var page = mapper.readValue(recovered, ActivationCodePage.class);
            var row = page.items().getFirst();
            assertThat(row.companyId()).isEqualTo(company.getId());
            assertThat(row.companyName()).isEqualTo(company.getName());
            assertThat(row.storeId()).isEqualTo(store.id());
            assertThat(row.storeName()).isEqualTo(store.name());
            assertThat(row.internalCode()).isEqualTo(store.internalCode());
            assertThat(row.storeCode()).isEqualTo(store.code());
            assertThat(row.licenseId()).isEqualTo(created.id());
            assertThat(row.reference()).isEqualTo(created.reference());
            assertThat(row.pairingCode()).isEqualTo(created.pairingCode());
            assertThat(row.pairingExpiresAt()).isEqualTo(pairingRepository.findFirstByCode(created.pairingCode()).orElseThrow().getExpiresAt());
            assertThat(page.serverNow()).isAfterOrEqualTo(created.serverNow()).isBefore(row.pairingExpiresAt());
            if (original != null) assertThat(row).isEqualTo(original);
            original = row;
        }
        assertThat(jdbc.queryForObject("select count(*) from saas_pairing_code where license_id=?", Integer.class, created.id())).isEqualTo(1);
    }

    @Test
    void activationCodeRecoveryExcludesInvalidStatesAndRetainsLegacyExpiryUntilConsumed() throws Exception {
        var company = company();
        var store = createStore(company, "001");
        var created = licenses.create(new CreateLicense(store.id()));
        Instant legacyExpiry = created.serverNow().plus(Duration.ofDays(7)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc.update("update saas_pairing_code set expires_at=? where code=?", Timestamp.from(legacyExpiry), created.pairingCode());
        assertThat(licenses.activationCodes(company.getId(), 0, 25).items().getFirst().pairingExpiresAt()).isEqualTo(legacyExpiry);
        for (String status : List.of("BLOQUEADA_MANUAL", "CADUCADA")) {
            jdbc.update("update saas_license set status=? where id=?", status, created.id());
            assertThat(licenses.activationCodes(company.getId(), 0, 25).items()).isEmpty();
        }
        jdbc.update("update saas_license set status='VALIDA',valid_until='2000-01-01T00:00:00Z' where id=?", created.id());
        assertThat(licenses.activationCodes(company.getId(), 0, 25).items()).isEmpty();
        jdbc.update("update saas_license set valid_until=? where id=?", Timestamp.from(EXPIRY), created.id());
        stores.updateActivity(store.id(), false);
        assertThat(licenses.activationCodes(company.getId(), 0, 25).items()).isEmpty();
        stores.updateActivity(store.id(), true);
        jdbc.update("update saas_pairing_code set expires_at='2000-01-01T00:00:00Z' where code=?", created.pairingCode());
        assertThat(licenses.activationCodes(company.getId(), 0, 25).items()).isEmpty();
        jdbc.update("update saas_pairing_code set expires_at=? where code=?", Timestamp.from(legacyExpiry), created.pairingCode());
        assertThat(licenses.activationCodes(company.getId(), 0, 25).items()).hasSize(1);
        mvc.perform(post("/api/v1/license/link").header("X-TPV-Link-Recovery-Token", "b".repeat(64))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(new LicenseSaasLinkRequest(
                                created.pairingCode(), UUID.randomUUID(), "ACTIVATION-CONSUMED", "public-key", null,
                                store.code(), "DEMO-00000000", "Demo", null, null, store.timeZoneId()))))
                .andExpect(status().isOk());
        assertThat(licenses.activationCodes(company.getId(), 0, 25).items()).isEmpty();
        assertThat(pairingRepository.findFirstByCode(created.pairingCode()).orElseThrow().getExpiresAt()).isEqualTo(legacyExpiry);
    }

    @Test
    void activationCodeRecoveryPaginatesWithStableTiesFiltersCompanyAndOmitsReplacedCodes() throws Exception {
        var company = company();
        var fixtures = sortingStores(company);
        var created = fixtures.subList(0, 27).stream().map(store -> licenses.create(new CreateLicense(store.getId()))).toList();
        var replacement = licenses.create(new CreateLicense(fixtures.getFirst().getId()), true);
        assertThat(licenses.activationCodes(company.getId(), 0, 100).items()).extracting(ActivationCodeRow::pairingCode)
                .contains(replacement.pairingCode()).doesNotContain(created.getFirst().pairingCode());
        var foreignStore = createStore(company(), "001");
        var foreignCode = licenses.create(new CreateLicense(foreignStore.id()));
        jdbc.update("update saas_pairing_code set created_at='2020-01-01T00:00:00Z' where company_id=?", company.getId());
        var expected = licenses.activationCodes(company.getId(), 0, 100).items().stream()
                .sorted(Comparator.comparing(row -> row.id().toString())).toList();
        var first = licenses.activationCodes(company.getId(), 0, 25);
        var second = licenses.activationCodes(company.getId(), 1, 25);
        assertThat(first.total()).isEqualTo(27);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.items()).containsExactlyElementsOf(expected.subList(0, 25));
        assertThat(second.items()).containsExactlyElementsOf(expected.subList(25, 27));
        assertThat(licenses.activationCodes(company.getId(), 0, 25).items()).isEqualTo(first.items());
        assertThat(licenses.activationCodes(company.getId(), 2, 25).items()).isEmpty();
        assertThat(licenses.activationCodes(UUID.randomUUID(), 0, 25).total()).isZero();
        assertThat(licenses.activationCodes(null, 0, 100).items()).extracting(ActivationCodeRow::pairingCode).contains(foreignCode.pairingCode());
        String endpoint = "/api/v1/admin/license-workspace/activation-codes";
        mvc.perform(get(endpoint).header("Authorization", basic("admin")).param("companyId", company.getId().toString()).param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.total").value(27)).andExpect(jsonPath("$.totalPages").value(2));
        for (String query : List.of("page=-1", "size=0", "size=101", "companyId=invalid")) {
            mvc.perform(get(endpoint + "?" + query).header("Authorization", basic("admin"))).andExpect(status().isBadRequest());
        }
    }

    @Test
    void individualLicenseReadUsesFreshTermsAndEffectiveStatusWithReadAuthorization() throws Exception {
        var company = company();
        var store = createStore(company, "001");
        var license = licenses.create(new CreateLicense(store.id()));
        mvc.perform(get("/api/v1/admin/license-workspace/{id}", license.id())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/license-workspace/{id}", license.id()).header("Authorization", basic("viewer")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(license.id().toString()))
                .andExpect(jsonPath("$.reference").value(license.reference()))
                .andExpect(jsonPath("$.status").value("VALIDA"));
        Instant renewedUntil = EXPIRY.plusSeconds(86400);
        mvc.perform(post("/api/v1/admin/licenses/{reference}/renew", license.reference()).header("Authorization", basic("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(new RenewLicenseRequest(renewedUntil, 5, 3))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/license-workspace/{id}", license.id()).header("Authorization", basic("viewer")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.validUntil").value(renewedUntil.toString()))
                .andExpect(jsonPath("$.maxWindows").value(5)).andExpect(jsonPath("$.maxPda").value(3));
        mvc.perform(post("/api/v1/admin/licenses/{reference}/block", license.reference()).header("Authorization", basic("admin")))
                .andExpect(status().isOk());
        assertThat(licenses.list(company.getId(), "", "VALIDA", null, null, null, 0, 25).items()).isEmpty();
        mvc.perform(get("/api/v1/admin/license-workspace/{id}", license.id()).header("Authorization", basic("viewer")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("BLOQUEADA_MANUAL"));
        jdbc.update("update saas_license set status='VALIDA',valid_until='2000-01-01T00:00:00Z' where id=?", license.id());
        mvc.perform(get("/api/v1/admin/license-workspace/{id}", license.id()).header("Authorization", basic("viewer")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CADUCADA"));
        assertThat(jdbc.queryForObject("select status from saas_license where id=?", String.class, license.id())).isEqualTo("VALIDA");
        mvc.perform(get("/api/v1/admin/license-workspace/{id}", UUID.randomUUID()).header("Authorization", basic("viewer")))
                .andExpect(status().isNotFound());
        String username = "no-license-read-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                insert into saas_admin_user(id,username,password_hash,active,created_at,must_change_password)
                select ?,?,password_hash,true,now(),false from saas_admin_user where username='admin'
                """, UUID.randomUUID(), username);
        mvc.perform(get("/api/v1/admin/license-workspace/{id}", license.id()).header("Authorization", basic(username)))
                .andExpect(status().isForbidden());
    }

    @Test
    void individualLicenseReadKeepsSharedStoresAndCompanyDebtByCurrency() throws Exception {
        var company = company();
        var one = createStore(company, "001");
        var two = createStore(company, "002");
        var license = licenseRepository.saveAndFlush(new SaasLicense(UUID.randomUUID(), company,
                "DETAIL-" + UUID.randomUUID(), EXPIRY, 2, 1, Instant.now()));
        for (var storeId : List.of(one.id(), two.id())) {
            pairingRepository.saveAndFlush(new SaasPairingCode(UUID.randomUUID(), company,
                    storeRepository.findById(storeId).orElseThrow(), license, "DETAIL-PAIR-" + UUID.randomUUID(), EXPIRY, Instant.now()));
        }
        UUID euroInvoice = invoice(company.getId(), "EUR", "100.25");
        invoice(company.getId(), "USD", "20.00");
        invoice(company().getId(), "EUR", "999.00");
        jdbc.update("""
                insert into saas_billing_payment(id,invoice_id,amount,method,reference,paid_at,created_at)
                values (?,?,'50.00','TRANSFERENCIA','DETAIL',now(),now())
                """, UUID.randomUUID(), euroInvoice);
        var response = mvc.perform(get("/api/v1/admin/license-workspace/{id}", license.getId()).header("Authorization", basic("viewer")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var detail = mapper.readValue(response, LicenseRow.class);
        assertThat(detail.companyId()).isEqualTo(company.getId());
        assertThat(detail.stores()).extracting(StoreLink::id).containsExactly(one.id(), two.id());
        assertThat(detail.billingScope()).isEqualTo("COMPANY");
        assertThat(detail.companyDebt()).extracting(CompanyDebt::currency).containsExactly("EUR", "USD");
        assertThat(detail.companyDebt().getFirst().outstanding()).isEqualByComparingTo("50.25");
        assertThat(detail.companyDebt().getFirst().overdue()).isEqualByComparingTo("50.25");
        assertThat(detail.companyDebt().get(1).outstanding()).isEqualByComparingTo("20.00");
        assertThat(detail).isEqualTo(licenses.list(company.getId(), "", null, null, null, null, 0, 25).items().getFirst());
    }

    @Test
    void regeneratingPairingForSecondStoreNeverMovesLicenseToFirstStore() throws Exception {
        var company = company(); createStore(company, "001"); var second = createStore(company, "002");
        var license = licenses.create(licenseRequest(company.getId(), second.id()));
        var response = mvc.perform(post("/api/v1/admin/licenses/{reference}/pairing-codes", license.reference())
                        .header("Authorization", basic("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String pairingCode = mapper.readTree(response).get("pairingCode").asText();
        assertThat(jdbc.queryForObject("select store_id from saas_pairing_code where code=?", UUID.class, pairingCode)).isEqualTo(second.id());
    }

    @Test
    void mutationPermissionsAreEnforcedInBackendAndReadPaginationIsValidated() throws Exception {
        var company = company(); var store = createStore(company, "001");
        mvc.perform(get("/api/v1/admin/stores")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/stores").header("Authorization", basic("viewer"))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/companies/{id}/stores", company.getId()).header("Authorization", basic("viewer"))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(new CreateStore("002", "Denied", fiscalAddress(), "Atlantic/Canary", TaxRegime.IVA, PRICE, StoreBillingPeriod.MONTHLY, 2, 1, EXPIRY))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/license-workspace").header("Authorization", basic("viewer"))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(licenseRequest(company.getId(), store.id()))))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/stores/{id}", store.id()).header("Authorization", basic("viewer"))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(new UpdateStore("Denied", fiscalAddress(), "Atlantic/Canary", false, TaxRegime.IVA, PRICE, StoreBillingPeriod.MONTHLY, 2, 1, EXPIRY))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/license-workspace?size=101").header("Authorization", basic("admin")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/license-workspace?status=UNKNOWN").header("Authorization", basic("admin")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void storesOwnTheirTaxRegimeAndLinkKeepsTheExistingContract() throws Exception {
        var company = company();
        jdbc.update("update saas_company set tax_regime=null where id=?", company.getId());
        var store = stores.create(company.getId(), new CreateStore("001", "Canarias", fiscalAddress(), "Atlantic/Canary",
                TaxRegime.IGIC, PRICE, StoreBillingPeriod.ANNUAL, 3, 2, EXPIRY));
        assertThat(store.taxRegime()).isEqualTo(TaxRegime.IGIC);
        assertThat(store.taxRegimeLocked()).isFalse();
        var created = licenses.create(new CreateLicense(store.id()));
        assertThat(stores.list(company.getId(), "", null, 0, 25).items().getFirst().taxRegimeLocked()).isTrue();
        UUID installation = UUID.randomUUID();
        mvc.perform(post("/api/v1/license/link").header("X-TPV-Link-Recovery-Token", "a".repeat(64))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(new LicenseSaasLinkRequest(
                                created.pairingCode(), installation, "STORE-TAX", "public-key", null, "001", "DEMO-00000000", "Demo", null, null, "Atlantic/Canary"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.impuestos").value("IGIC"))
                .andExpect(jsonPath("$.commercialProfile").value("MAYORISTA"))
                .andExpect(jsonPath("$.maxWindows").value(3)).andExpect(jsonPath("$.maxPda").value(2));
        var changed = new UpdateStore(store.name(), fiscalAddress(), store.timeZoneId(), true,
                TaxRegime.IVA, PRICE, StoreBillingPeriod.ANNUAL, 3, 2, EXPIRY);
        assertThatThrownBy(() -> stores.update(store.id(), changed, true)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> jdbc.update("update saas_store set tax_regime='IVA' where id=?", store.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void newCompaniesRequireAnExplicitStoreProfileAndLegacyUpdatesPreserveIt() throws Exception {
        var company = company();
        jdbc.update("update saas_company set commercial_profile=null where id=?", company.getId());
        var legacyRequest = new CreateStore("001", "Tienda", fiscalAddress(), "Atlantic/Canary",
                TaxRegime.IGIC, PRICE, StoreBillingPeriod.MONTHLY, 2, 1, EXPIRY);
        mvc.perform(post("/api/v1/admin/companies/{id}/stores", company.getId())
                        .header("Authorization", basic("admin")).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(legacyRequest)))
                .andExpect(status().isBadRequest());
        assertThat(stores.list(company.getId(), "", null, 0, 25).items()).isEmpty();
        var explicitRequest = new CreateStore("001", "Tienda", fiscalAddress(), "Atlantic/Canary",
                TaxRegime.IGIC, PRICE, StoreBillingPeriod.MONTHLY, 2, 1, EXPIRY, CommercialProfile.MINORISTA);
        var response = mvc.perform(post("/api/v1/admin/companies/{id}/stores", company.getId())
                        .header("Authorization", basic("admin")).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(explicitRequest)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.commercialProfile").value("MINORISTA"))
                .andReturn().getResponse().getContentAsString();
        UUID storeId = UUID.fromString(mapper.readTree(response).get("id").asText());
        var legacyUpdate = new UpdateStore("Renamed", fiscalAddress(), "Atlantic/Canary", true,
                TaxRegime.IGIC, PRICE, StoreBillingPeriod.MONTHLY, 2, 1, EXPIRY);
        mvc.perform(put("/api/v1/admin/stores/{id}", storeId).header("Authorization", basic("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(legacyUpdate)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.commercialProfile").value("MINORISTA"));
        assertThat(jdbc.queryForObject("select commercial_profile from saas_company where id=?", String.class, company.getId())).isNull();
    }

    @Test
    void legacyCreateInheritsOnlyTheExistingCompanyProfile() {
        var company = company();
        jdbc.update("update saas_company set commercial_profile='MINORISTA' where id=?", company.getId());
        assertThat(createStore(company, "001").commercialProfile()).isEqualTo(CommercialProfile.MINORISTA);
    }

    @Test
    void linkingAndValidationUseEachStoreProfileAndPropagateLaterStoreChanges() throws Exception {
        var company = company();
        var linked = new LinkedHashMap<StoreRow, LinkedStore>();
        for (var profile : CommercialProfile.values()) {
            String code = profile == CommercialProfile.MAYORISTA ? "001" : "002";
            var store = stores.create(company.getId(), new CreateStore(code, profile.name(), fiscalAddress(), "Atlantic/Canary",
                    TaxRegime.IGIC, PRICE, StoreBillingPeriod.MONTHLY, 2, 1, EXPIRY, profile));
            var license = licenses.create(new CreateLicense(store.id()));
            UUID installationId = UUID.randomUUID();
            String installationReference = "PROFILE-" + code;
            var response = mvc.perform(post("/api/v1/license/link").header("X-TPV-Link-Recovery-Token", "a".repeat(64))
                            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(new LicenseSaasLinkRequest(
                                    license.pairingCode(), installationId, installationReference, "public-key", null, code,
                                    "DEMO-00000000", "Demo", null, null, "Atlantic/Canary"))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.commercialProfile").value(profile.name()))
                    .andReturn().getResponse().getContentAsString();
            String token = mapper.readTree(response).get("installationToken").asText();
            linked.put(store, new LinkedStore(new LicenseSaasValidationRequest(
                    installationId, installationReference, store.id(), license.reference(), "hash"), token));
        }
        for (var entry : linked.entrySet()) assertValidatedProfile(entry.getValue(), entry.getKey().commercialProfile());
        var wholesale = linked.keySet().stream().filter(store -> store.commercialProfile() == CommercialProfile.MAYORISTA)
                .findFirst().orElseThrow();
        var updated = stores.update(wholesale.id(), new UpdateStore(wholesale.name(), wholesale.storeAddress(), wholesale.timeZoneId(),
                true, wholesale.taxRegime(), PRICE, StoreBillingPeriod.MONTHLY, 2, 1, EXPIRY, CommercialProfile.MINORISTA));
        assertThat(updated.commercialProfile()).isEqualTo(CommercialProfile.MINORISTA);
        for (var installation : linked.values()) assertValidatedProfile(installation, CommercialProfile.MINORISTA);
        assertThat(jdbc.queryForObject("select commercial_profile from saas_company where id=?", String.class, company.getId()))
                .isEqualTo("MAYORISTA");
    }

    private void assertValidatedProfile(LinkedStore installation, CommercialProfile expected) throws Exception {
        mvc.perform(post("/api/v1/license/validate").header("X-TPV-Installation-Token", installation.token())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(installation.request())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.commercialProfile").value(expected.name()));
    }

    private record LinkedStore(LicenseSaasValidationRequest request, String token) { }

    @Test
    void servicePriceIsDecimalAndPeriodicAndInitialLicenseConfigurationIsExplicit() throws Exception {
        var company = company();
        var created = stores.create(company.getId(), new CreateStore("001", "Annual", fiscalAddress(), "Atlantic/Canary",
                TaxRegime.IVA, new BigDecimal("199.95"), StoreBillingPeriod.ANNUAL, 4, 3, EXPIRY));
        assertThat(created.servicePrice()).isEqualByComparingTo("199.95");
        assertThat(created.billingPeriod()).isEqualTo(StoreBillingPeriod.ANNUAL);
        assertThat(created.validUntil()).isEqualTo(EXPIRY);
        mvc.perform(get("/api/v1/admin/stores").param("companyId", company.getId().toString()).header("Authorization", basic("admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].servicePrice").value("199.95"));
        for (BigDecimal invalid : List.of(new BigDecimal("-0.01"), new BigDecimal("12.345"), new BigDecimal("100000000000000000.00"))) {
            assertThatThrownBy(() -> stores.create(company.getId(), new CreateStore("002", "Invalid", fiscalAddress(), "Atlantic/Canary",
                    TaxRegime.IVA, invalid, StoreBillingPeriod.MONTHLY, 1, 0, EXPIRY))).isInstanceOf(ResponseStatusException.class);
        }
        assertThatThrownBy(() -> stores.create(company.getId(), new CreateStore("002", "Missing expiry", fiscalAddress(), "Atlantic/Canary",
                TaxRegime.IVA, PRICE, StoreBillingPeriod.MONTHLY, 1, 0, null))).isInstanceOf(ResponseStatusException.class);
        var licensed = licenses.create(new CreateLicense(created.id()));
        var entity = licenseRepository.findById(licensed.id()).orElseThrow();
        assertThat(entity.getMaxWindows()).isEqualTo(4); assertThat(entity.getMaxPda()).isEqualTo(3);
        assertThat(entity.getValidUntil()).isEqualTo(EXPIRY);
    }

    @Test
    void storeConfigurationRenewsTheLicenseAtomicallyWithoutUnblockingIt() {
        var company = company(); var store = createStore(company, "001");
        var created = licenses.create(new CreateLicense(store.id()));
        jdbc.update("update saas_license set status='BLOQUEADA_MANUAL' where id=?", created.id());
        Instant extended = EXPIRY.plusSeconds(86400);
        var request = new UpdateStore("Renewed", fiscalAddress(), "Atlantic/Canary", true,
                TaxRegime.IVA, new BigDecimal("299.00"), StoreBillingPeriod.ANNUAL, 5, 4, extended);
        assertThatThrownBy(() -> stores.update(store.id(), request)).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(403));
        assertThat(stores.list(company.getId(), "", null, 0, 25).items().getFirst().maxWindows()).isEqualTo(2);
        var updated = stores.update(store.id(), request, true);
        var license = licenseRepository.findById(created.id()).orElseThrow();
        assertThat(updated.maxWindows()).isEqualTo(5); assertThat(license.getMaxWindows()).isEqualTo(5);
        assertThat(license.getMaxPda()).isEqualTo(4); assertThat(license.getValidUntil()).isEqualTo(extended);
        assertThat(license.getLicenseVersion()).isEqualTo(2);
        assertThat(license.getStatus()).isEqualTo(LicenseSaasStatus.BLOQUEADA_MANUAL);
        assertThatThrownBy(() -> licenses.create(new CreateLicense(store.id()), true)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void simultaneousGenerateRequestsCreateOnlyOneLicenseAndOneUsablePairing() throws Exception {
        var store = createStore(company(), "001");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var futures = java.util.stream.IntStream.range(0, 3).mapToObj(ignored -> executor.submit(() -> {
                start.await(); return licenses.create(new CreateLicense(store.id()), true);
            })).toList();
            start.countDown();
            var ids = new HashSet<UUID>();
            for (var future : futures) ids.add(future.get(30, TimeUnit.SECONDS).id());
            assertThat(ids).hasSize(1);
            assertThat(jdbc.queryForObject("select count(*) from saas_license where store_id=?", Long.class, store.id())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from saas_pairing_code where store_id=? and consumed_at is null and revoked_at is null and expires_at>now()", Long.class, store.id())).isEqualTo(1);
        }
    }

    @Test
    void linkingWaitsForStoreConfigurationBeforeLockingTheLicense() throws Exception {
        var store = createStore(company(), "001");
        var created = licenses.create(new CreateLicense(store.id()));
        UUID installation = UUID.randomUUID();
        var request = new LicenseSaasLinkRequest(created.pairingCode(), installation, "CONCURRENT-LINK", "public-key",
                null, "001", "DEMO-00000000", "Demo", null, null, "Atlantic/Canary");
        var attempts = new ArrayList<Future<LicenseSaasLinkResponse>>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                jdbc.query("select pg_advisory_xact_lock(hashtextextended(?::text, 0))", rows -> { }, store.companyId());
                jdbc.query("select id from saas_store where id=? for update", rows -> { }, store.id());
                attempts.add(executor.submit(() -> linking.link(request, null, "b".repeat(64))));
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
                assertThat(waitingForCompany).as("Link must wait on the company before taking a license lock").isTrue();
                jdbc.query("select id from saas_license where id=? for update nowait", rows -> { }, created.id());
                jdbc.update("update saas_store set max_windows=4 where id=?", store.id());
                jdbc.update("update saas_license set max_windows=4,license_version=license_version+1 where id=?", created.id());
            });
            var linked = attempts.getFirst().get(10, TimeUnit.SECONDS);
            assertThat(linked.maxWindows()).isEqualTo(4);
            assertThat(linked.licenseVersion()).isEqualTo(2);
        }
    }

    @Test
    void legacySharedLicensesAreNeverMovedOrDuplicatedByStoreGeneration() {
        var company = company(); var first = createStore(company, "001"); var second = createStore(company, "002");
        var legacy = licenseRepository.saveAndFlush(new SaasLicense(UUID.randomUUID(), company, "LEGACY-" + UUID.randomUUID(), EXPIRY, 2, 1, Instant.now()));
        for (UUID id : List.of(first.id(), second.id())) {
            pairingRepository.saveAndFlush(new SaasPairingCode(UUID.randomUUID(), company, storeRepository.findById(id).orElseThrow(), legacy,
                    "PAIR-" + UUID.randomUUID(), EXPIRY, Instant.now()));
        }
        assertThatThrownBy(() -> licenses.create(new CreateLicense(first.id()), true)).isInstanceOf(ResponseStatusException.class);
        var request = new UpdateStore(first.name(), fiscalAddress(), first.timeZoneId(), true,
                TaxRegime.IVA, PRICE, StoreBillingPeriod.MONTHLY, 5, 1, EXPIRY);
        assertThatThrownBy(() -> stores.update(first.id(), request, true)).isInstanceOf(ResponseStatusException.class);
        assertThat(licenses.list(company.getId(), "", null, null, null, null, 0, 25).total()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select store_id from saas_license where id=?", UUID.class, legacy.getId())).isNull();
        jdbc.update("update saas_store set valid_until=null,service_price=null,billing_period=null where id=?", first.id());
        var priceOnly = new UpdateStore("Legacy price configured", fiscalAddress(), first.timeZoneId(), true,
                TaxRegime.IVA, new BigDecimal("120.00"), StoreBillingPeriod.ANNUAL, first.maxWindows(), first.maxPda(), null);
        var updated = stores.update(first.id(), priceOnly);
        assertThat(updated.servicePrice()).isEqualByComparingTo("120.00");
        assertThat(updated.validUntil()).isNull();
        assertThat(jdbc.queryForObject("select valid_until from saas_license where id=?", java.sql.Timestamp.class, legacy.getId()).toInstant()).isEqualTo(EXPIRY);
    }

    @Test
    void sortingIsGlobalBeforePaginationAndRejectsUnknownSqlFragments() throws Exception {
        var company = company();
        var one = licenses.create(new CreateLicense(createStore(company, "001").id()));
        var two = licenses.create(new CreateLicense(createStore(company, "002").id()));
        var three = licenses.create(new CreateLicense(createStore(company, "003").id()));
        jdbc.update("update saas_license set max_windows=9 where id=?", one.id());
        jdbc.update("update saas_license set max_windows=3 where id=?", two.id());
        jdbc.update("update saas_license set max_windows=6 where id=?", three.id());
        assertThat(licenses.list(company.getId(), "", null, null, null, null, 0, 1, "maxWindows", "ASC").items())
                .extracting(LicenseRow::id).containsExactly(two.id());
        assertThat(licenses.list(company.getId(), "", null, null, null, null, 1, 1, "maxWindows", "ASC").items())
                .extracting(LicenseRow::id).containsExactly(three.id());
        assertThat(licenses.list(company.getId(), "", null, null, null, null, 0, 1, "maxWindows", "DESC").items())
                .extracting(LicenseRow::id).containsExactly(one.id());
        for (String column : List.of("reference", "companyName", "storeCode", "status", "validUntil", "lastValidatedAt", "lastSyncAt", "maxPda", "activeInstallations", "billingStatus")) {
            assertThat(licenses.list(company.getId(), "", null, null, null, null, 0, 2, column, "DESC").items()).hasSize(2);
        }
        mvc.perform(get("/api/v1/admin/license-workspace").param("sortBy", "l.id;drop table saas_license").header("Authorization", basic("admin")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/license-workspace?sortDirection=SIDEWAYS").header("Authorization", basic("admin")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void storeSortingOrdersDecimalPricesAndExpiryBeforePagingWithNullsLast() throws Exception {
        var company = company();
        var fixtures = sortingStores(company);
        for (String sortBy : List.of("servicePrice", "validUntil")) {
            for (String direction : List.of("ASC", "DESC")) {
                Comparator<SaasStore> order = sortBy.equals("servicePrice")
                        ? Comparator.comparing(SaasStore::getServicePrice,
                                Comparator.nullsLast(direction.equals("ASC") ? Comparator.<BigDecimal>naturalOrder() : Comparator.<BigDecimal>reverseOrder()))
                        : Comparator.comparing(SaasStore::getValidUntil,
                                Comparator.nullsLast(direction.equals("ASC") ? Comparator.<Instant>naturalOrder() : Comparator.<Instant>reverseOrder()));
                var expected = fixtures.stream().sorted(order.thenComparing(store -> store.getId().toString()))
                        .map(SaasStore::getId).toList();
                var first = stores.list(company.getId(), "", null, 0, 25, sortBy, direction);
                var second = stores.list(company.getId(), "", null, 1, 25, sortBy, direction);
                assertThat(first.total()).isEqualTo(32);
                assertThat(first.totalPages()).isEqualTo(2);
                assertThat(first.items()).extracting(StoreRow::id).containsExactlyElementsOf(expected.subList(0, 25));
                assertThat(second.items()).extracting(StoreRow::id).containsExactlyElementsOf(expected.subList(25, 32));
                assertThat(second.items().subList(5, 7)).allMatch(row -> row.servicePrice() == null && row.validUntil() == null);
            }
        }
        mvc.perform(get("/api/v1/admin/stores").header("Authorization", basic("viewer"))
                        .param("companyId", company.getId().toString()).param("sortBy", "servicePrice").param("sortDirection", "ASC"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(25))
                .andExpect(jsonPath("$.items[0].id").value(fixtures.get(29).getId().toString()))
                .andExpect(jsonPath("$.items[0].servicePrice").value("1.25"));
    }

    @Test
    void storeSortingUsesUuidTiesAndWhitelistedColumnsWithLegacyDefaultOrder() throws Exception {
        var company = company();
        var fixtures = sortingStores(company);
        var expected = fixtures.stream().map(SaasStore::getId).sorted(Comparator.comparing(UUID::toString)).toList();
        for (String direction : List.of("ASC", "DESC")) {
            var first = stores.list(company.getId(), "", null, 0, 25, "maxPda", direction);
            var second = stores.list(company.getId(), "", null, 1, 25, "maxPda", direction);
            assertThat(first.items()).extracting(StoreRow::id).containsExactlyElementsOf(expected.subList(0, 25));
            assertThat(second.items()).extracting(StoreRow::id).containsExactlyElementsOf(expected.subList(25, 32));
            assertThat(stores.list(company.getId(), "", null, 0, 25, "maxPda", direction).items()).isEqualTo(first.items());
        }
        assertThat(stores.list(company.getId(), "", null, 0, 25).items()).extracting(StoreRow::code)
                .containsExactlyElementsOf(fixtures.subList(0, 25).stream().map(SaasStore::getCode).toList());
        for (String column : List.of("internalCode", "companyName", "code", "name", "active", "taxRegime",
                "commercialProfile", "billingPeriod", "maxWindows", "installations", "activeInstallations", "lastSyncAt", "createdAt")) {
            assertThat(stores.list(company.getId(), "", true, 0, 25, column, "DESC").items()).hasSize(25);
        }
        for (String column : List.of("s.id;drop table saas_store", "unknown", "companyName DESC")) {
            mvc.perform(get("/api/v1/admin/stores").header("Authorization", basic("viewer")).param("sortBy", column))
                    .andExpect(status().isBadRequest());
        }
        for (String direction : List.of("SIDEWAYS", "ASC;drop table saas_store")) {
            mvc.perform(get("/api/v1/admin/stores").header("Authorization", basic("viewer")).param("sortDirection", direction))
                    .andExpect(status().isBadRequest());
        }
        assertThat(stores.list(company.getId(), "", null, 0, 25).total()).isEqualTo(32);
    }

    @Test
    void individualStoreReadReturnsFreshDetailsAndRequiresReadPermission() throws Exception {
        var company = company();
        var original = createStore(company, "001");
        var updated = stores.update(original.id(), new UpdateStore("Updated store", fiscalAddress(), original.timeZoneId(),
                false, original.taxRegime(), new BigDecimal("35.50"), StoreBillingPeriod.ANNUAL, 2, 1, EXPIRY,
                CommercialProfile.MINORISTA));
        mvc.perform(get("/api/v1/admin/stores/{id}", original.id())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/stores/{id}", original.id()).header("Authorization", basic("viewer")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(original.id().toString()))
                .andExpect(jsonPath("$.companyId").value(company.getId().toString()))
                .andExpect(jsonPath("$.name").value(updated.name())).andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.servicePrice").value("35.50"))
                .andExpect(jsonPath("$.billingPeriod").value("ANNUAL"))
                .andExpect(jsonPath("$.commercialProfile").value("MINORISTA"));
        mvc.perform(get("/api/v1/admin/stores/{id}", UUID.randomUUID()).header("Authorization", basic("viewer")))
                .andExpect(status().isNotFound());
        String username = "no-store-read-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                insert into saas_admin_user(id,username,password_hash,active,created_at,must_change_password)
                select ?,?,password_hash,true,now(),false from saas_admin_user where username='admin'
                """, UUID.randomUUID(), username);
        mvc.perform(get("/api/v1/admin/stores/{id}", original.id()).header("Authorization", basic(username)))
                .andExpect(status().isForbidden());
    }

    private List<SaasStore> sortingStores(SaasCompany company) {
        jdbc.update("""
                insert into saas_company_operations(company_id,plan_name,billing_status,support_status,updated_at)
                values (?,'ENTERPRISE','PENDIENTE','NORMAL',now())
                """, company.getId());
        var fixtures = new ArrayList<SaasStore>();
        for (int index = 1; index <= 32; index++) {
            var store = new SaasStore(UUID.randomUUID(), company, String.format(Locale.ROOT, "%03d", index),
                    "Sorting store " + index, fiscalAddress(), "Atlantic/Canary", Instant.now());
            if (index <= 30) {
                store.updateServicePrice(new BigDecimal(31 - index).add(new BigDecimal("0.25")), StoreBillingPeriod.MONTHLY);
                store.updateLicenseConfiguration(EXPIRY.plusSeconds(((index * 7) % 30) * 86400L), index, 0);
            }
            fixtures.add(store);
        }
        return storeRepository.saveAllAndFlush(fixtures);
    }

    @Test
    void existingLicensesCannotBypassSeparateRenewAndPairingPermissions() throws Exception {
        var company = company(); var store = createStore(company, "001");
        String username = "store-editor-" + UUID.randomUUID().toString().substring(0, 8);
        UUID user = UUID.randomUUID(), role = UUID.randomUUID();
        jdbc.update("insert into saas_admin_role(id,name,created_at) values(?,?,now())", role, username);
        jdbc.update("insert into saas_admin_user(id,username,password_hash,active,created_at,must_change_password) select ?,?,password_hash,true,now(),false from saas_admin_user where username='admin'", user, username);
        jdbc.update("insert into saas_admin_user_role(user_id,role_id) values(?,?)", user, role);
        for (String permission : List.of("ADD_COMPANY", "EDIT_COMPANY_DATA")) {
            jdbc.update("insert into saas_admin_role_permission(role_id,permission_code) values(?,?)", role, permission);
        }
        var body = mapper.writeValueAsBytes(new CreateLicense(store.id()));
        mvc.perform(post("/api/v1/admin/license-workspace").header("Authorization", basic(username)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/license-workspace").header("Authorization", basic(username)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        var renewal = new UpdateStore(store.name(), fiscalAddress(), store.timeZoneId(), true, TaxRegime.IVA, PRICE, StoreBillingPeriod.MONTHLY, 3, 1, EXPIRY);
        mvc.perform(put("/api/v1/admin/stores/{id}", store.id()).header("Authorization", basic(username)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(renewal)))
                .andExpect(status().isForbidden());
        jdbc.update("insert into saas_admin_role_permission(role_id,permission_code) values(?,'RENEW_LICENSE'),(?,'REGENERATE_PAIRING_CODE')", role, role);
        mvc.perform(put("/api/v1/admin/stores/{id}", store.id()).header("Authorization", basic(username)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(renewal)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/license-workspace").header("Authorization", basic(username)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void revocationRequiresPairingPermissionAndPreservesHistoryAndLicense() throws Exception {
        var store = createStore(company(), "001");
        var response = mvc.perform(post("/api/v1/admin/license-workspace").header("Authorization", basic("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(new CreateLicense(store.id()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var created = mapper.readValue(response, CreatedLicense.class);
        assertThat(created.pairingCodeId()).isNotNull();
        var original = pairingRepository.findById(created.pairingCodeId()).orElseThrow();
        String endpoint = "/api/v1/admin/license-workspace/activation-codes/" + created.pairingCodeId();
        mvc.perform(delete(endpoint)).andExpect(status().isUnauthorized());
        for (String user : List.of("viewer", adminWithPermissions("ADD_COMPANY"), adminWithPermissions())) {
            mvc.perform(delete(endpoint).header("Authorization", basic(user))).andExpect(status().isForbidden());
        }
        assertThat(pairingRepository.findById(created.pairingCodeId()).orElseThrow().usableAt(Instant.now())).isTrue();
        String operator = adminWithPermissions("REGENERATE_PAIRING_CODE");
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(delete(endpoint).header("Authorization", basic(operator)))
                    .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(delete("/api/v1/admin/license-workspace/activation-codes/{id}", UUID.randomUUID())
                .header("Authorization", basic(operator))).andExpect(status().isNotFound());
        var revoked = pairingRepository.findById(created.pairingCodeId()).orElseThrow();
        assertThat(revoked.getRevokedAt()).isNotNull();
        assertThat(revoked.getRevocationReason()).isEqualTo("ADMIN_REVOKED");
        assertThat(revoked.getExpiresAt()).isEqualTo(original.getExpiresAt());
        assertThat(revoked.getConsumedAt()).isNull();
        assertThat(licenseRepository.findById(created.id()).orElseThrow().getStatus()).isEqualTo(LicenseSaasStatus.VALIDA);
        assertThat(licenses.activationCodes(store.companyId(), 0, 25).items()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from saas_installation where store_id=?", Long.class, store.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from saas_admin_audit_log where action='REVOKE_PAIRING_CODE' and target_id=?",
                Long.class, created.pairingCodeId().toString())).isEqualTo(1);
        assertThatThrownBy(() -> linking.link(pairingRequest(created, store), null, "b".repeat(64)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void revocationOfExpiredOrConsumedCodeIsIdempotentAndKeepsInstallationRecovery() throws Exception {
        var store = createStore(company(), "001");
        var expired = licenses.create(new CreateLicense(store.id()));
        jdbc.update("update saas_pairing_code set expires_at='2000-01-01T00:00:00Z' where id=?", expired.pairingCodeId());
        mvc.perform(delete("/api/v1/admin/license-workspace/activation-codes/{id}", expired.pairingCodeId())
                .header("Authorization", basic("admin"))).andExpect(status().isNoContent());
        assertThat(pairingRepository.findById(expired.pairingCodeId()).orElseThrow().getRevokedAt()).isNull();
        var current = licenses.create(new CreateLicense(store.id()), true);
        var request = pairingRequest(current, store);
        var linked = linking.link(request, null, "b".repeat(64));
        mvc.perform(delete("/api/v1/admin/license-workspace/activation-codes/{id}", current.pairingCodeId())
                .header("Authorization", basic("admin"))).andExpect(status().isNoContent());
        var consumed = pairingRepository.findById(current.pairingCodeId()).orElseThrow();
        assertThat(consumed.getConsumedAt()).isNotNull();
        assertThat(consumed.getRevokedAt()).isNull();
        var retry = linking.link(request, null, "b".repeat(64));
        assertThat(retry.installationToken()).isEqualTo(linked.installationToken());
        assertThat(jdbc.queryForObject("select count(*) from saas_installation where store_id=? and active", Long.class, store.id())).isEqualTo(1);
    }

    @Test
    void legacyRegenerationReplacesOtherLicenseCodeForSameStoreAndKeepsOriginalExpiry() {
        var company = company(); var store = createStore(company, "001");
        var first = licenses.create(new CreateLicense(store.id()));
        var oldExpiry = pairingRepository.findById(first.pairingCodeId()).orElseThrow().getExpiresAt();
        var otherLicense = new SaasLicense(UUID.randomUUID(), company, "LEGACY-" + UUID.randomUUID(), EXPIRY, 2, 1, Instant.now());
        otherLicense.assignStore(storeRepository.findById(store.id()).orElseThrow());
        licenseRepository.saveAndFlush(otherLicense);
        var regenerated = admin.regeneratePairingCode(otherLicense.getReference());
        var old = pairingRepository.findById(first.pairingCodeId()).orElseThrow();
        assertThat(old.getRevocationReason()).isEqualTo("REPLACED");
        assertThat(old.getExpiresAt()).isEqualTo(oldExpiry);
        assertThat(old.usableAt(Instant.now())).isFalse();
        assertThat(regenerated.pairingCode()).isNotEqualTo(first.pairingCode());
        assertThat(licenses.activationCodes(company.getId(), 0, 25).items()).extracting(ActivationCodeRow::pairingCode)
                .containsExactly(regenerated.pairingCode());
    }

    @Test
    void unusableLegacyLicenseCannotReplaceUsableStoreCode() throws Exception {
        var company = company(); var store = createStore(company, "001");
        var valid = licenses.create(new CreateLicense(store.id()));
        var other = new SaasLicense(UUID.randomUUID(), company, "LEGACY-" + UUID.randomUUID(), EXPIRY, 2, 1, Instant.now());
        other.assignStore(storeRepository.findById(store.id()).orElseThrow());
        licenseRepository.saveAndFlush(other);
        for (String state : List.of("BLOQUEADA_MANUAL", "CADUCADA")) {
            jdbc.update("update saas_license set status=? where id=?", state, other.getId());
            mvc.perform(post("/api/v1/admin/licenses/{reference}/pairing-codes", other.getReference())
                    .header("Authorization", basic("admin"))).andExpect(status().isConflict());
        }
        jdbc.update("update saas_license set status='VALIDA',valid_until='2000-01-01T00:00:00Z' where id=?", other.getId());
        mvc.perform(post("/api/v1/admin/licenses/{reference}/pairing-codes", other.getReference())
                .header("Authorization", basic("admin"))).andExpect(status().isConflict());
        assertThat(pairingRepository.findById(valid.pairingCodeId()).orElseThrow().usableAt(Instant.now())).isTrue();
        assertThat(licenses.activationCodes(company.getId(), 0, 25).items()).extracting(ActivationCodeRow::id)
                .containsExactly(valid.pairingCodeId());
    }

    @Test
    void failedIssuanceRollsBackRevocationInBothRoutes() {
        var store = createStore(company(), "001");
        var valid = licenses.create(new CreateLicense(store.id()));
        jdbc.execute("""
                create function test_reject_pairing_insert() returns trigger language plpgsql as $$
                begin
                    if new.store_id = '%s'::uuid then
                        raise exception 'Injected pairing uniqueness failure' using errcode = '23505';
                    end if;
                    return new;
                end $$
                """.formatted(store.id()));
        jdbc.execute("create trigger test_reject_pairing before insert on saas_pairing_code for each row execute function test_reject_pairing_insert()");
        try {
            for (Runnable issue : List.<Runnable>of(() -> licenses.create(new CreateLicense(store.id()), true),
                    () -> admin.regeneratePairingCode(valid.reference()))) {
                assertThatThrownBy(issue::run).isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
                var unchanged = pairingRepository.findById(valid.pairingCodeId()).orElseThrow();
                assertThat(unchanged.getRevokedAt()).isNull();
                assertThat(unchanged.usableAt(Instant.now())).isTrue();
                assertThat(openCodes(store.id())).isEqualTo(1);
            }
        } finally {
            jdbc.execute("drop trigger test_reject_pairing on saas_pairing_code");
            jdbc.execute("drop function test_reject_pairing_insert()");
        }
    }

    @Test
    void bothIssuingRoutesSerializeAndNeverPublishTwoOpenCodesForAStore() throws Exception {
        var store = createStore(company(), "001");
        var first = licenses.create(new CreateLicense(store.id()));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var futures = java.util.stream.IntStream.range(0, 6).mapToObj(index -> executor.submit(() -> {
                start.await();
                return index % 2 == 0 ? licenses.create(new CreateLicense(store.id()), true).pairingCode()
                        : admin.regeneratePairingCode(first.reference()).pairingCode();
            })).toList();
            start.countDown();
            var codes = new HashSet<String>();
            for (var future : futures) assertThat(codes.add(future.get(20, TimeUnit.SECONDS))).isTrue();
            assertThat(openCodes(store.id())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from saas_pairing_code where store_id=?", Long.class, store.id())).isEqualTo(7);
            assertThat(jdbc.queryForObject("select count(*) from saas_pairing_code where store_id=? and revocation_reason='REPLACED'", Long.class, store.id())).isEqualTo(6);
        }
    }

    @Test
    void staleDeletionRacingWithReplacementCannotRevokeTheNewCode() throws Exception {
        var store = createStore(company(), "001");
        var original = licenses.create(new CreateLicense(store.id()));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var replacement = executor.submit(() -> { start.await(); return licenses.create(new CreateLicense(store.id()), true); });
            var deletion = executor.submit(() -> { start.await(); licenses.revokeActivationCode(original.pairingCodeId()); return true; });
            start.countDown();
            var fresh = replacement.get(20, TimeUnit.SECONDS);
            assertThat(deletion.get(20, TimeUnit.SECONDS)).isTrue();
            licenses.revokeActivationCode(original.pairingCodeId());
            assertThat(pairingRepository.findById(fresh.pairingCodeId()).orElseThrow().usableAt(Instant.now())).isTrue();
            assertThat(licenses.activationCodes(store.companyId(), 0, 25).items()).extracting(ActivationCodeRow::id)
                    .containsExactly(fresh.pairingCodeId());
            assertThat(openCodes(store.id())).isEqualTo(1);
        }
    }

    @Test
    void linkAndDeleteAreSerializedWithoutDeletingAConsumedInstallation() throws Exception {
        var store = createStore(company(), "001");
        var created = licenses.create(new CreateLicense(store.id()));
        var request = pairingRequest(created, store);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var link = executor.submit(() -> {
                start.await();
                try { linking.link(request, null, "b".repeat(64)); return true; }
                catch (ResponseStatusException error) { assertThat(error.getStatusCode().value()).isEqualTo(409); return false; }
            });
            var revoke = executor.submit(() -> { start.await(); licenses.revokeActivationCode(created.pairingCodeId()); return true; });
            start.countDown();
            boolean consumed = link.get(20, TimeUnit.SECONDS);
            assertThat(revoke.get(20, TimeUnit.SECONDS)).isTrue();
            var pairing = pairingRepository.findById(created.pairingCodeId()).orElseThrow();
            assertThat(pairing.getConsumedAt() != null).isEqualTo(consumed);
            assertThat(pairing.getRevokedAt() != null).isEqualTo(!consumed);
            assertThat(jdbc.queryForObject("select count(*) from saas_installation where store_id=? and active", Long.class, store.id()))
                    .isEqualTo(consumed ? 1L : 0L);
            assertThat(openCodes(store.id())).isZero();
        }
    }

    @Test
    void persistenceRejectsDuplicateCodeAndTwoUnconsumedUnrevokedRows() {
        var store = createStore(company(), "001");
        var first = licenses.create(new CreateLicense(store.id()));
        assertThatThrownBy(() -> jdbc.update("""
                insert into saas_pairing_code(id,company_id,store_id,license_id,code,expires_at,created_at)
                select ?,company_id,store_id,license_id,?,expires_at,now() from saas_pairing_code where id=?
                """, UUID.randomUUID(), "OTHER-" + UUID.randomUUID(), first.pairingCodeId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        licenses.revokeActivationCode(first.pairingCodeId());
        assertThatThrownBy(() -> jdbc.update("""
                insert into saas_pairing_code(id,company_id,store_id,license_id,code,expires_at,created_at)
                select ?,company_id,store_id,license_id,code,expires_at,now() from saas_pairing_code where id=?
                """, UUID.randomUUID(), first.pairingCodeId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from saas_pairing_code where store_id=?", Long.class, store.id())).isEqualTo(1);
    }

    private long openCodes(UUID storeId) {
        return jdbc.queryForObject("select count(*) from saas_pairing_code where store_id=? and consumed_at is null and revoked_at is null", Long.class, storeId);
    }

    private LicenseSaasLinkRequest pairingRequest(CreatedLicense created, StoreRow store) {
        return new LicenseSaasLinkRequest(created.pairingCode(), UUID.randomUUID(), "PAIRING-LIFECYCLE", "public-key",
                null, store.code(), "DEMO-00000000", "Demo", null, null, store.timeZoneId());
    }

    private SaasCompany company() {
        int number = COMPANY_NUMBER.incrementAndGet();
        return companies.saveAndFlush(new SaasCompany(UUID.randomUUID(), "Store test " + number,
                validCif("B" + number + "0"), TaxpayerType.SOCIEDAD, TaxRegime.IVA, CommercialProfile.MAYORISTA, fiscalAddress(), Instant.now()));
    }
    private StoreRow createStore(SaasCompany company, String code) {
        return stores.create(company.getId(), new CreateStore(code, "Tienda " + code, fiscalAddress(), "Atlantic/Canary", TaxRegime.IVA, PRICE, StoreBillingPeriod.MONTHLY, 2, 1, EXPIRY));
    }
    private CreateLicense licenseRequest(UUID company, UUID store) {
        return new CreateLicense(store);
    }
    private UUID invoice(UUID company, String currency, String amount) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into saas_billing_invoice(id,company_id,number,concept,amount,currency,status,issued_at,due_at,created_at,
                    series,fiscal_year,tax_regime,tax_base,tax_rate,tax_amount,fiscal_status)
                values (?,?,?,'Test',?,?,'PENDIENTE','2020-01-01T00:00:00Z','2020-02-01T00:00:00Z',now(),
                    'TEST',2020,'IVA',?,'0.00','0.00','CALCULATED')
                """, id, company, "TEST-" + UUID.randomUUID(), amount, currency, amount);
        return id;
    }
    private static String basic(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":admin").getBytes(StandardCharsets.UTF_8));
    }

    private String adminWithPermissions(String... permissions) {
        String username = "activation-admin-" + UUID.randomUUID().toString().substring(0, 8);
        UUID userId = UUID.randomUUID(), roleId = UUID.randomUUID();
        jdbc.update("insert into saas_admin_role(id,name,created_at) values(?,?,now())", roleId, username);
        jdbc.update("""
                insert into saas_admin_user(id,username,password_hash,active,created_at,must_change_password)
                select ?,?,password_hash,true,now(),false from saas_admin_user where username='admin'
                """, userId, username);
        jdbc.update("insert into saas_admin_user_role(user_id,role_id) values(?,?)", userId, roleId);
        for (String permission : permissions) {
            jdbc.update("insert into saas_admin_role_permission(role_id,permission_code) values(?,?)", roleId, permission);
        }
        return username;
    }
}
