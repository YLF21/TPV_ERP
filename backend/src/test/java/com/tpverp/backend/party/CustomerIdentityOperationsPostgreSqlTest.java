package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL isolation, upgrade and atomicity tests; never uses the application's database defaults. */
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
@Timeout(90)
class CustomerIdentityOperationsPostgreSqlTest {

    private static final String URL = System.getenv("TPV_ERP_TEST_DB_URL");
    private static final String USER = System.getenv("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = schemaName();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADDRESS = """
            {"linea1":"Test 1","ciudad":"Las Palmas","codigoPostal":"35001","provincia":"Las Palmas","pais":"ES"}
            """;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static CustomerIdentityOperations operations;
    private static Fixture legacyFixture;
    private static UUID legacyCifId;

    @BeforeAll
    static void setup() {
        migrate(SCHEMA, "241");
        var dataSource = dataSource(SCHEMA);
        jdbc = new JdbcTemplate(dataSource);
        var manager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(20);
        operations = new CustomerIdentityOperations(jdbc, manager);
        legacyFixture = fixture(jdbc);
        legacyCifId = insertCustomer(jdbc, legacyFixture, DocumentType.CIF, "B12345674", "C-001-000001");
        migrate(SCHEMA, "242");
    }

    @AfterAll
    static void cleanup() throws Exception {
        dropSchema(SCHEMA);
    }

    @Test
    void upgradesLegacyCifWithoutRewritingItsIdentityAndRejectsCrossTypeNormalizedDuplicates() {
        assertThat(jdbc.queryForMap("select tipo_documento,numero_documento from cliente where id=?", legacyCifId))
                .containsEntry("tipo_documento", "CIF").containsEntry("numero_documento", "B12345674");
        assertThatThrownBy(() -> insertCustomer(jdbc, legacyFixture, DocumentType.NIF,
                "B-12345674", "C-001-000002"))
                .isInstanceOf(DuplicateKeyException.class);
        var dni = insertCustomer(jdbc, legacyFixture, DocumentType.DNI, "00000001R", "C-001-000003");
        assertThatThrownBy(() -> insertCustomer(jdbc, legacyFixture, DocumentType.PASAPORTE,
                "0000 0001-R", "C-001-000004"))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("select numero_documento from cliente where id=?", String.class, dni))
                .isEqualTo("00000001R");

        var otherCompany = fixture(jdbc);
        insertCustomer(jdbc, otherCompany, DocumentType.DNI, "00000001R", "C-001-000001");
    }

    @Test
    void databaseAndJavaUseTheSameSeparatorKeyWithoutRemovingInternalPunctuation() {
        for (var input : List.of(" \t12 345-678z\r\n", "00000001-r", "AB.C/1", "A\u00a0B")) {
            assertThat(jdbc.queryForObject("select customer_document_key(?)", String.class, input))
                    .isEqualTo(CustomerDocumentIdentity.normalizeNumber(input));
        }
    }

    @Test
    void centralLinkRequiresBothIdentifierAndPositiveRevisionAndIsUniqueWithinCompany() {
        var fixture = fixture(jdbc);
        var id = insertCustomer(jdbc, fixture, DocumentType.PASAPORTE, "PASS1", "C-001-000001");
        var centralId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("update cliente set saas_customer_id=? where id=?", centralId, id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update cliente set saas_identity_revision=1 where id=?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "update cliente set saas_customer_id=?,saas_identity_revision=0 where id=?", centralId, id))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("update cliente set saas_customer_id=?,saas_identity_revision=1 where id=?", centralId, id);
        var otherId = insertCustomer(jdbc, fixture, DocumentType.PASAPORTE, "PASS2", "C-001-000002");
        assertThatThrownBy(() -> jdbc.update(
                "update cliente set saas_customer_id=?,saas_identity_revision=1 where id=?", centralId, otherId))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("select saas_identity_revision from cliente where id=?", Long.class, id))
                .isEqualTo(1);
    }

    @Test
    void preparingAnIntentSurvivesOuterRollbackAndReusesTheSameOperationAndCustomerIds() {
        var fixture = fixture(jdbc);
        var identity = identity("PERSISTED");
        var first = transaction.execute(status -> {
            var operation = operations.prepare(fixture.company().getId(), fixture.storeId(), null, null, null, identity);
            status.setRollbackOnly();
            return operation;
        });

        var second = operations.prepare(fixture.company().getId(), fixture.storeId(), null, null, null, identity);

        assertThat(second).isEqualTo(first);
        assertThat(state(first.operationId())).isEqualTo("PENDING");
        assertThat(operationCount(fixture)).isEqualTo(1);
    }

    @Test
    void simultaneousSameStoreReservationsReuseOneDurableIntent() throws Exception {
        var fixture = fixture(jdbc);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<CustomerIdentityOperations.Operation>>();
            for (int index = 0; index < 8; index++) {
                futures.add(executor.submit(() -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return operations.prepare(fixture.company().getId(), fixture.storeId(), null, null, null, identity("RACE"));
                }));
            }
            start.countDown();
            var results = new ArrayList<CustomerIdentityOperations.Operation>();
            for (var future : futures) results.add(future.get(15, TimeUnit.SECONDS));

            assertThat(results).extracting(CustomerIdentityOperations.Operation::operationId).containsOnly(results.getFirst().operationId());
            assertThat(results).extracting(CustomerIdentityOperations.Operation::customerId).containsOnly(results.getFirst().customerId());
            assertThat(operationCount(fixture)).isEqualTo(1);
        }
    }

    @Test
    void anotherStoreCannotReuseAnIntentAndCancelledIntentDoesNotReuseItsTombstone() {
        var fixture = fixture(jdbc);
        var operation = operations.prepare(fixture.company().getId(), fixture.storeId(), null, null, null, identity("OWNED"));
        var otherStore = insertStore(jdbc, fixture.company().getId(), "002");

        assertThatThrownBy(() -> operations.prepare(fixture.company().getId(), otherStore, null, null, null, identity("OWNED")))
                .isInstanceOf(CustomerIdentityException.class).hasMessage("CUSTOMER_IDENTITY_CONFLICT");
        operations.cancellationPending(operation.operationId());
        assertThat(state(operation.operationId())).isEqualTo("CANCEL_PENDING");
        operations.cancelled(operation.operationId());
        var replacement = operations.prepare(fixture.company().getId(), otherStore, null, null, null, identity("OWNED"));

        assertThat(replacement.operationId()).isNotEqualTo(operation.operationId());
        assertThat(state(operation.operationId())).isEqualTo("CANCELLED");
    }

    @Test
    void abandonedRecoverySkipsAnActiveRowLockAndRecoversAnOldCrashedIntent() throws Exception {
        var fixture = fixture(jdbc);
        var active = operations.prepare(fixture.company().getId(), fixture.storeId(), null, null, null, identity("ACTIVE"));
        var crashed = operations.prepare(fixture.company().getId(), fixture.storeId(), null, null, null, identity("CRASHED"));
        jdbc.update("update customer_identity_operation set created_at=now()-interval '10 minutes' where company_id=?",
                fixture.company().getId());
        var recent = operations.prepare(fixture.company().getId(), fixture.storeId(), null, null, null, identity("RECENT"));
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = executor.submit(() -> transaction.executeWithoutResult(status -> {
                operations.lock(active.operationId());
                locked.countDown();
                try { assertThat(release.await(15, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                operations.recoverAbandoned();
                assertThat(state(active.operationId())).isEqualTo("PENDING");
                assertThat(state(crashed.operationId())).isEqualTo("CANCEL_PENDING");
                assertThat(state(recent.operationId())).isEqualTo("PENDING");
            } finally { release.countDown(); }
            holder.get(10, TimeUnit.SECONDS);
        }
        operations.recoverAbandoned();
        assertThat(state(active.operationId())).isEqualTo("CANCEL_PENDING");
    }

    @Test
    void committedStateCustomerAndOutboxRollbackTogetherWhenALaterWriteFails() {
        var fixture = fixture(jdbc);
        var operation = operations.prepare(fixture.company().getId(), fixture.storeId(), null, null, null, identity("ATOMIC"));
        var customer = customer(fixture, operation.customerId(), "ATOMIC");
        var coordinator = coordinatorWithJdbcOutbox();
        var approval = approval(operation, customer);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            operations.lock(operation.operationId());
            insertCustomer(jdbc, fixture, customer.getId(), DocumentType.PASAPORTE, "ATOMIC", customer.getClientId());
            coordinator.complete(approval, customer);
            jdbc.update("update cliente set saas_customer_id=?,saas_identity_revision=? where id=?",
                    customer.getSaasCustomerId(), customer.getSaasIdentityRevision(), customer.getId());
            assertThat(state(operation.operationId())).isEqualTo("LOCAL_COMMITTED");
            assertThat(outboxCount(customer.getId())).isEqualTo(1);
            throw new IllegalStateException("forced-later-write-failure");
        })).isInstanceOf(IllegalStateException.class).hasMessage("forced-later-write-failure");

        assertThat(state(operation.operationId())).isEqualTo("PENDING");
        assertThat(outboxCount(customer.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from cliente where id=?", Integer.class, customer.getId())).isZero();
    }

    @Test
    void commitsCustomerLinkIntentAndOneOutboxEventAtomically() {
        var fixture = fixture(jdbc);
        var operation = operations.prepare(fixture.company().getId(), fixture.storeId(), null, null, null, identity("COMMITTED"));
        var customer = customer(fixture, operation.customerId(), "COMMITTED");
        var approval = approval(operation, customer);
        var coordinator = coordinatorWithJdbcOutbox();

        transaction.executeWithoutResult(status -> {
            operations.lock(operation.operationId());
            insertCustomer(jdbc, fixture, customer.getId(), DocumentType.PASAPORTE, "COMMITTED", customer.getClientId());
            coordinator.complete(approval, customer);
            jdbc.update("update cliente set saas_customer_id=?,saas_identity_revision=? where id=?",
                    customer.getSaasCustomerId(), customer.getSaasIdentityRevision(), customer.getId());
        });

        assertThat(state(operation.operationId())).isEqualTo("LOCAL_COMMITTED");
        assertThat(outboxCount(customer.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select saas_customer_id from cliente where id=?", UUID.class, customer.getId()))
                .isEqualTo(approval.reservation().customerId());
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> coordinator.complete(approval, customer)))
                .isInstanceOf(CustomerIdentityException.class).hasMessage("CUSTOMER_IDENTITY_CONFLICT");
        assertThat(outboxCount(customer.getId())).isEqualTo(1);
    }

    @Test
    void coordinatorMarksTheDurableIntentForCancellationAfterUncertainHttpRollback() {
        var fixture = fixture(jdbc);
        var central = mock(CustomerIdentitySaasClient.class);
        when(central.reserve(any(), any())).thenThrow(CustomerIdentityException.unavailable());
        var coordinator = new CustomerIdentityCoordinator(operations, central, mock(SyncOutboxService.class));
        var candidate = customer(fixture, UUID.randomUUID(), "TIMEOUT");

        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                coordinator.reserve(fixture.company().getId(), fixture.storeId(), null, identity("TIMEOUT"), candidate)))
                .isInstanceOf(CustomerIdentityException.class).hasMessage("CUSTOMER_IDENTITY_SAAS_UNAVAILABLE");

        assertThat(jdbc.queryForObject("select state from customer_identity_operation where company_id=?", String.class,
                fixture.company().getId())).isEqualTo("CANCEL_PENDING");
        assertThat(operationCount(fixture)).isEqualTo(1);
    }

    @Test
    void upgradeStopsOnExistingNormalizedDuplicatesWithoutMergingOrRewritingEitherCustomer() throws Exception {
        var duplicateSchema = schemaName();
        try {
            migrate(duplicateSchema, "241");
            var oldJdbc = new JdbcTemplate(dataSource(duplicateSchema));
            var fixture = fixture(oldJdbc);
            var first = insertCustomer(oldJdbc, fixture, DocumentType.NIF, "12345678Z", "C-001-000001");
            var second = insertCustomer(oldJdbc, fixture, DocumentType.PASAPORTE, "12 345678-Z", "C-001-000002");

            assertThatThrownBy(() -> migrate(duplicateSchema, "242"))
                    .isInstanceOf(FlywayException.class).hasStackTraceContaining("CUSTOMER_DOCUMENT_DUPLICATE");

            assertThat(oldJdbc.queryForList("select id from cliente where empresa_id=?", UUID.class, fixture.company().getId()))
                    .containsExactlyInAnyOrder(first, second);
            assertThat(oldJdbc.queryForObject("select numero_documento from cliente where id=?", String.class, second))
                    .isEqualTo("12 345678-Z");
            assertThat(oldJdbc.queryForObject("select count(*) from information_schema.columns where table_schema=? and table_name='cliente' and column_name='saas_customer_id'",
                    Integer.class, duplicateSchema)).isZero();
        } finally { dropSchema(duplicateSchema); }
    }

    private static CustomerIdentityCoordinator coordinatorWithJdbcOutbox() {
        var outbox = mock(SyncOutboxService.class);
        doAnswer(invocation -> {
            SyncOutboundEventCommand command = invocation.getArgument(0);
            jdbc.update("""
                    insert into sync_outbox(id,event_id,empresa_id,tienda_id,tipo_entidad,entidad_id,operacion,payload,creado_en,actualizado_en)
                    values (?,?,?,?,?,?,?,cast(? as jsonb),now(),now())
                    """, UUID.randomUUID(), UUID.randomUUID(), command.companyId(), command.storeId(), command.entityType(),
                    command.entityId(), command.operation().name(), JSON.writeValueAsString(command.payload()));
            return null;
        }).when(outbox).enqueue(any());
        return new CustomerIdentityCoordinator(operations, mock(CustomerIdentitySaasClient.class), outbox);
    }

    private static CustomerIdentityCoordinator.Approval approval(CustomerIdentityOperations.Operation operation, Customer customer) {
        return new CustomerIdentityCoordinator.Approval(operation,
                new CustomerIdentitySaasClient.Reservation(operation.operationId(), UUID.randomUUID(), 1L,
                        operation.documentType().name(), operation.documentNumber()), CustomerIdentityCoordinator.profile(customer));
    }

    private static Customer customer(Fixture fixture, UUID id, String number) {
        var customer = new Customer(id, fixture.company(), "Synthetic customer", DocumentType.PASAPORTE, number,
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        customer.assignClientCode(fixture.storeId(), "C-001-000001");
        return customer;
    }

    private static CustomerDocumentIdentity identity(String number) {
        return CustomerDocumentIdentity.validate(DocumentType.PASAPORTE, number);
    }

    private static String state(UUID operationId) {
        return jdbc.queryForObject("select state from customer_identity_operation where operation_id=?", String.class, operationId);
    }

    private static int operationCount(Fixture fixture) {
        return jdbc.queryForObject("select count(*) from customer_identity_operation where company_id=?", Integer.class, fixture.company().getId());
    }

    private static int outboxCount(UUID customerId) {
        return jdbc.queryForObject("select count(*) from sync_outbox where entidad_id=?", Integer.class, customerId);
    }

    private static Fixture fixture(JdbcTemplate target) {
        var company = new Company("TEST-" + UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT), "Synthetic company", Map.of(
                "linea1", "Test 1", "ciudad", "Las Palmas", "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES"));
        target.update("insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values (?,?,?,cast(? as jsonb))",
                company.getId(), company.getTaxId(), company.getRazonSocial(), ADDRESS);
        return new Fixture(company, insertStore(target, company.getId(), "001"));
    }

    private static UUID insertStore(JdbcTemplate target, UUID companyId, String code) {
        var id = UUID.randomUUID();
        target.update("""
                insert into tienda(id,empresa_id,nombre,direccion,address_normalized_hash,timezone,moneda,locale,codigo_tienda)
                values (?,?,'Synthetic store',cast(? as jsonb),?,'Atlantic/Canary','EUR','es-ES',?)
                """, id, companyId, ADDRESS, "TEST-" + id, code);
        return id;
    }

    private static UUID insertCustomer(JdbcTemplate target, Fixture fixture, DocumentType type, String number, String code) {
        var id = UUID.randomUUID();
        insertCustomer(target, fixture, id, type, number, code);
        return id;
    }

    private static void insertCustomer(JdbcTemplate target, Fixture fixture, UUID id, DocumentType type, String number, String code) {
        target.update("""
                insert into cliente(id,empresa_id,client_id,client_code_store_id,nombre_fiscal,tipo_documento,numero_documento)
                values (?,?,?,?,'Synthetic customer',?,?)
                """, id, fixture.company().getId(), code, fixture.storeId(), type.name(), number);
    }

    private static DriverManagerDataSource dataSource(String schema) {
        return new DriverManagerDataSource(URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public", USER, PASSWORD);
    }

    private static void migrate(String schema, String target) {
        FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                .dataSource(URL, USER, PASSWORD).schemas(schema).defaultSchema(schema).createSchemas(true)
                .target(MigrationVersion.fromVersion(target)).load().migrate();
    }

    private static String schemaName() {
        return "customer_identity_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static void dropSchema(String schema) throws Exception {
        if (!schema.matches("customer_identity_[0-9a-f]{32}")) throw new IllegalArgumentException("Unexpected test schema");
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD); var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
        }
    }

    private record Fixture(Company company, UUID storeId) { }
}
