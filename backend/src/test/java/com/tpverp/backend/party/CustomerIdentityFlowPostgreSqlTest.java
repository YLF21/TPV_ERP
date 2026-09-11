package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The real service/JPA/code allocator/outbox transaction, using only an explicitly supplied test database. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class, CustomerService.class, CustomerIdentityOperations.class,
        CustomerIdentityCoordinator.class, SyncOutboxService.class, PartyCodeAllocator.class,
        CustomerIdentityFlowPostgreSqlTest.Configuration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class CustomerIdentityFlowPostgreSqlTest {
    private static final String URL = System.getenv("TPV_ERP_TEST_DB_URL");
    private static final String USER = System.getenv("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "customer_identity_flow_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired CustomerService service;
    @Autowired CompanyRepository companies;
    @Autowired StoreRepository stores;
    @Autowired CustomerRepository customers;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PartyContext context;
    @MockitoBean MemberLoyaltyService memberLoyalty;
    @MockitoBean CustomerIdentitySaasClient central;
    @MockitoSpyBean SyncOutboxService outbox;
    private Company company;
    private Store store;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @BeforeEach
    void fixture() {
        company = companies.save(new Company("TEST-" + UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT),
                "Synthetic company", Map.of("linea1", "Test street", "ciudad", "Las Palmas",
                        "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES")));
        store = stores.save(PartyTestData.store(company));
        when(context.currentCompany()).thenReturn(company);
        when(context.currentStore()).thenReturn(store);
        when(central.reserve(any(), any())).thenAnswer(invocation -> {
            CustomerIdentityOperations.Operation operation = invocation.getArgument(0);
            return new CustomerIdentitySaasClient.Reservation(operation.operationId(),
                    operation.expectedCustomerId() == null ? UUID.randomUUID() : operation.expectedCustomerId(),
                    operation.expectedRevision() == null ? 1L : operation.expectedRevision() + 1L,
                    operation.documentType().name(), operation.documentNumber());
        });
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (!SCHEMA.matches("customer_identity_flow_[0-9a-f]{32}")) throw new IllegalStateException("Unexpected schema");
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD); var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + SCHEMA + " cascade");
        }
    }

    @Test
    void committedCreatePersistsCustomerLinkCodeAndExactlyOneImmutableOutboxEvent() {
        var result = service.create(command("00000001R"));
        var stored = customers.findById(result.id()).orElseThrow();
        assertThat(stored.getSaasCustomerId()).isNotNull();
        assertThat(stored.getSaasIdentityRevision()).isEqualTo(1L);
        assertThat(stored.getDocumentNumber()).isEqualTo("00000001R");
        assertThat(count("cliente", "empresa_id")).isEqualTo(1);
        assertThat(count("sync_outbox", "empresa_id")).isEqualTo(1);
        assertThat(states()).containsExactly("LOCAL_COMMITTED");
        assertThat(jdbc.queryForObject("select payload->>'clientId' from sync_outbox where entidad_id=?",
                String.class, result.id())).isEqualTo(result.clientId());
        assertThat(jdbc.queryForObject("select payload->>'operationId' from sync_outbox where entidad_id=?",
                String.class, result.id())).isNotBlank();
    }

    @Test
    void anOutboxFailureRollsBackTheCustomerCounterAndLinkButPreservesCancellationIntent() {
        doThrow(new IllegalStateException("forced-outbox-failure")).when(outbox).enqueue(any());
        assertThatThrownBy(() -> service.create(command("00000001R")))
                .isInstanceOf(IllegalStateException.class).hasMessage("forced-outbox-failure");
        assertThat(count("cliente", "empresa_id")).isZero();
        assertThat(count("sync_outbox", "empresa_id")).isZero();
        assertThat(states()).containsExactly("CANCEL_PENDING");
        assertThat(jdbc.queryForObject("select count(*) from party_code_counter where scope_id=?", Integer.class, store.getId())).isZero();
        reset(outbox);
    }

    @Test
    void batchReservationFailureLeavesNoPartialCustomersOrOutboxAndCancelsAllAttemptedReservations() {
        doAnswer(invocation -> {
            CustomerIdentityOperations.Operation operation = invocation.getArgument(0);
            if (operation.documentNumber().equals("00000002W")) throw CustomerIdentityException.duplicate();
            return new CustomerIdentitySaasClient.Reservation(operation.operationId(), UUID.randomUUID(), 1L,
                    operation.documentType().name(), operation.documentNumber());
        }).when(central).reserve(any(), any());
        assertThatThrownBy(() -> service.createBatch(List.of(command("00000001R"), command("00000002W"))))
                .isInstanceOf(CustomerIdentityException.class).hasMessage("CUSTOMER_DOCUMENT_DUPLICATE");
        assertThat(count("cliente", "empresa_id")).isZero();
        assertThat(count("sync_outbox", "empresa_id")).isZero();
        assertThat(states()).containsExactlyInAnyOrder("CANCEL_PENDING", "CANCEL_PENDING");
        assertThat(jdbc.queryForObject("select count(*) from party_code_counter where scope_id=?", Integer.class, store.getId())).isZero();
    }

    @Test
    void updatingIdentityPreservesTheSameLocalCustomerAndAddsAnOutboxEventWithTheNextRevision() {
        var result = service.create(command("00000001R"));
        var oldCentralId = customers.findById(result.id()).orElseThrow().getSaasCustomerId();
        var updated = service.update(result.id(), command("00000002W"));
        var stored = customers.findById(result.id()).orElseThrow();
        assertThat(updated.id()).isEqualTo(result.id());
        assertThat(stored.getSaasCustomerId()).isEqualTo(oldCentralId);
        assertThat(stored.getSaasIdentityRevision()).isEqualTo(2L);
        assertThat(stored.getDocumentNumber()).isEqualTo("00000002W");
        assertThat(count("cliente", "empresa_id")).isEqualTo(1);
        assertThat(count("sync_outbox", "empresa_id")).isEqualTo(2);
        assertThat(states()).containsExactlyInAnyOrder("LOCAL_COMMITTED", "LOCAL_COMMITTED");
    }

    private int count(String table, String companyColumn) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + companyColumn + "=?", Integer.class, company.getId());
    }
    private List<String> states() {
        return jdbc.queryForList("select state from customer_identity_operation where company_id=?", String.class, company.getId());
    }
    private static CustomerService.CustomerCommand command(String number) {
        return new CustomerService.CustomerCommand("Synthetic customer", DocumentType.DNI, number, null,
                null, null, null, BigDecimal.ZERO, false, null);
    }
    @TestConfiguration
    static class Configuration { @Bean Clock clock() { return Clock.systemUTC(); } }
}
