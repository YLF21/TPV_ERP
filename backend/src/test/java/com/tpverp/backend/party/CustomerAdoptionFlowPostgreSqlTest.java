package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CompanyRepository;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import com.tpverp.backend.sync.SyncOutboxService;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class, CustomerService.class, CustomerIdentityOperations.class,
        CustomerIdentityCoordinator.class, CustomerAdoptionOperations.class, CustomerAdoptionService.class,
        SyncOutboxService.class, PartyCodeAllocator.class, CustomerAdoptionFlowPostgreSqlTest.Configuration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class CustomerAdoptionFlowPostgreSqlTest {
    static final String URL = System.getenv("TPV_ERP_TEST_DB_URL");
    static final String USER = System.getenv("TPV_ERP_TEST_DB_USER");
    static final String PASSWORD = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
    static final String SCHEMA = "customer_adoption_flow_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired CustomerAdoptionService service;
    @Autowired CustomerAdoptionOperations operations;
    @Autowired CustomerService normalCustomers;
    @Autowired CompanyRepository companies;
    @Autowired StoreRepository stores;
    @Autowired CustomerRepository customers;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired javax.sql.DataSource dataSource;
    @MockitoBean PartyContext context;
    @MockitoBean MemberLoyaltyService memberLoyalty;
    @MockitoBean CustomerIdentitySaasClient central;
    @MockitoSpyBean SyncOutboxService outbox;
    Company company;
    Store store;
    UUID centralId;

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @BeforeEach void fixture() {
        company = companies.save(new Company("TEST-" + UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT),
                "Synthetic company", Map.of("linea1", "Test street", "ciudad", "Las Palmas",
                        "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES")));
        store = stores.save(PartyTestData.store(company));
        centralId = UUID.randomUUID();
        when(context.currentCompany()).thenReturn(company);
        when(context.currentStore()).thenReturn(store);
        when(central.reserveAdoption(any())).thenAnswer(call -> {
            CustomerAdoptionOperations.Operation operation = call.getArgument(0);
            return new CustomerAdoptionApi.Reservation(operation.operationId(), operation.customerId(),
                    new CustomerAdoptionApi.Profile(centralId, 0L, "CENTRAL-001", "Selected central customer",
                            DocumentType.DNI, "00000001R", null, null, null, true, null));
        });
    }

    @AfterAll static void cleanup() throws Exception {
        if (!SCHEMA.matches("customer_adoption_flow_[0-9a-f]{32}")) throw new IllegalStateException("Unexpected schema");
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD); var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + SCHEMA + " cascade");
        }
    }

    @Test void realJpaCommitsRevisionZeroCopyCounterAndOneOutboxWithoutAnyNewIdentityRegistration() {
        var result = service.adopt(request());
        var stored = customers.findById(result.customer().id()).orElseThrow();
        assertThat(stored.getSaasCustomerId()).isEqualTo(centralId);
        assertThat(stored.getSaasIdentityRevision()).isZero();
        assertThat(stored.getSaasClientCode()).isEqualTo("CENTRAL-001");
        assertThat(stored.getClientId()).isNotEqualTo("CENTRAL-001");
        assertThat(count("cliente", "empresa_id")).isEqualTo(1);
        assertThat(count("sync_outbox", "empresa_id")).isEqualTo(1);
        assertThat(count("customer_identity_operation", "company_id")).isZero();
        assertThat(states()).containsExactly("LOCAL_COMMITTED");
        assertThat(jdbc.queryForObject("select payload::text from sync_outbox where entidad_id=?", String.class, stored.getId()))
                .contains("operationId").doesNotContain("clientId", "fiscalName");
        var repeated = service.adopt(request());
        assertThat(repeated.customer().id()).isEqualTo(stored.getId());
        assertThat(count("sync_outbox", "empresa_id")).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("update cliente set saas_identity_revision=-1 where id=?", stored.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(central, times(1)).reserveAdoption(any());
        verify(central, never()).reserve(any(), any());
        var committed = operations.prepare(company.getId(), store.getId(), null, centralId, 0,
                CustomerDocumentIdentity.validate(DocumentType.DNI, "00000001R"));
        assertThat(committed.state()).isEqualTo("LOCAL_COMMITTED");
        assertThat(committed.customerId()).isEqualTo(stored.getId());
        assertThat(states()).containsExactly("LOCAL_COMMITTED");
    }

    @Test void outboxFailureRollsBackJpaCustomerCounterAndLocalIdentityButRetainsCancellation() {
        doThrow(new IllegalStateException("synthetic failure")).when(outbox).enqueue(any());
        assertThatThrownBy(() -> service.adopt(request())).isInstanceOf(IllegalStateException.class);
        assertThat(count("cliente", "empresa_id")).isZero();
        assertThat(count("sync_outbox", "empresa_id")).isZero();
        assertThat(states()).containsExactly("CANCEL_PENDING");
        assertThat(jdbc.queryForObject("select count(*) from party_code_counter where scope_id=?", Integer.class, store.getId())).isZero();
        reset(outbox);
    }

    @Test void timeoutPersistsOnlyCancellableIntentAndRetryCannotReviveItBeforeCancellation() {
        doThrow(CustomerIdentityException.unavailable()).when(central).reserveAdoption(any());
        assertThatThrownBy(() -> service.adopt(request())).hasMessage("CUSTOMER_IDENTITY_SAAS_UNAVAILABLE");
        assertThat(count("cliente", "empresa_id")).isZero();
        assertThat(count("sync_outbox", "empresa_id")).isZero();
        assertThat(states()).containsExactly("CANCEL_PENDING");
        assertThatThrownBy(() -> service.adopt(request())).hasMessage("CUSTOMER_IDENTITY_CONFLICT");
        verify(central, times(1)).reserveAdoption(any());
        service.retryCancellations();
        assertThat(states()).containsExactly("CANCELLED");
    }

    @Test void durablePrepareSurvivesRollbackAndReusesOperationAndLocalId() {
        var identity = CustomerDocumentIdentity.validate(DocumentType.DNI, "00000001R");
        var first = new TransactionTemplate(transactions).execute(status -> {
            var value = operations.prepare(company.getId(), store.getId(), null, centralId, 0, identity);
            status.setRollbackOnly(); return value;
        });
        assertThat(operations.prepare(company.getId(), store.getId(), null, centralId, 0, identity)).isEqualTo(first);
        assertThat(states()).containsExactly("PENDING");
        assertThatThrownBy(() -> operations.prepare(company.getId(), store.getId(), null, centralId, 1, identity))
                .hasMessage("CUSTOMER_IDENTITY_CONFLICT");
    }

    @Test void doubleClickCreatesOneCustomerAndOneEventThroughRealCounterLock() throws Exception {
        assertThat(((com.zaxxer.hikari.HikariDataSource) dataSource).getMaximumPoolSize()).isEqualTo(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { assertThat(start.await(10, TimeUnit.SECONDS)).isTrue(); return service.adopt(request()); });
            var b = pool.submit(() -> { assertThat(start.await(10, TimeUnit.SECONDS)).isTrue(); return service.adopt(request()); });
            start.countDown();
            assertThat(a.get(30, TimeUnit.SECONDS).customer().id()).isEqualTo(b.get(30, TimeUnit.SECONDS).customer().id());
        }
        assertThat(count("cliente", "empresa_id")).isEqualTo(1);
        assertThat(count("sync_outbox", "empresa_id")).isEqualTo(1);
        assertThat(states()).containsExactly("LOCAL_COMMITTED");
    }

    @Test void adoptionRejectsAmbientTransactionWithoutLeavingIndependentWrites() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> service.adopt(request())))
                .isInstanceOf(IllegalStateException.class).hasMessage("Customer adoption cannot join an existing transaction");
        assertThat(count("customer_adoption_operation", "company_id")).isZero();
        assertThat(count("cliente", "empresa_id")).isZero();
        assertThat(count("sync_outbox", "empresa_id")).isZero();
    }

    @Test void laterIdentityEditKeepsCentralCodeAndNeverReplacesItWithTheNewStoreCode() {
        var adopted = service.adopt(request());
        when(central.reserve(any(), any())).thenAnswer(call -> {
            CustomerIdentityOperations.Operation operation = call.getArgument(0);
            Map<String, Object> profile = call.getArgument(1);
            assertThat(profile.get("clientId")).isEqualTo("CENTRAL-001");
            return new CustomerIdentitySaasClient.Reservation(operation.operationId(), centralId, 1L, "DNI", "00000002W");
        });
        var edited = normalCustomers.update(adopted.customer().id(), new CustomerService.CustomerCommand(
                "Edited profile", DocumentType.DNI, "00000002W", null, null, null, null, BigDecimal.ZERO, false, null));
        var stored = customers.findById(edited.id()).orElseThrow();
        assertThat(stored.getClientId()).isEqualTo(adopted.customer().clientId());
        assertThat(stored.getSaasClientCode()).isEqualTo("CENTRAL-001");
        assertThat(stored.getSaasIdentityRevision()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select payload->>'clientId' from sync_outbox where entidad_id=? and tipo_entidad='CUSTOMER_IDENTITY'",
                String.class, edited.id())).isEqualTo("CENTRAL-001");
    }

    private CustomerAdoptionApi.Adopt request() { return new CustomerAdoptionApi.Adopt(centralId, 0L, DocumentType.DNI, "00000001R"); }
    private int count(String table, String field) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + field + "=?", Integer.class, company.getId());
    }
    private List<String> states() {
        return jdbc.queryForList("select state from customer_adoption_operation where company_id=?", String.class, company.getId());
    }
    @TestConfiguration static class Configuration { @Bean Clock clock() { return Clock.systemUTC(); } }
}
