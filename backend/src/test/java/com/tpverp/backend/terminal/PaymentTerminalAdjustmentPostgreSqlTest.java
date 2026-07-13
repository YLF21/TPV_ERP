package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PaymentTerminalAdjustmentService.class)
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentTerminalAdjustmentPostgreSqlTest {
    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "tpv_terminal_adjustment_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-07-13T10:00:00Z");

    static { execute("create schema " + SCHEMA); }

    @Autowired private PaymentTerminalAdjustmentService service;
    @Autowired private PaymentTerminalOperationRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @BeforeEach
    void clearDatabase() { jdbc.execute("truncate table empresa cascade"); }

    @AfterAll
    static void dropSchema() { execute("drop schema if exists " + SCHEMA + " cascade"); }

    @Test
    void concurrentRefundsWithSameIdempotencyKeyReplayOneReservation() throws Exception {
        var fixture = approvedCharge("10.00");
        var operationId1 = UUID.randomUUID();
        var operationId2 = UUID.randomUUID();
        var results = concurrently(
                () -> reserveRefund(fixture, operationId1, "same-key", "4.00", 'b'),
                () -> reserveRefund(fixture, operationId2, "same-key", "4.00", 'b'));

        assertThat(results).allMatch(Result::successful);
        assertThat(results).allMatch(result -> result.operation().getId().equals(operationId1)
                || result.operation().getId().equals(operationId2));
        assertThat(repository.findAll()).filteredOn(operation -> operation.getOperationType() == PaymentTerminalOperationType.REFUND)
                .hasSize(1);
    }

    @Test
    void concurrentRefundsWithDifferentKeysReserveOnlyAvailableBalance() throws Exception {
        var fixture = approvedCharge("10.00");
        var results = concurrently(
                () -> reserveRefund(fixture, UUID.randomUUID(), "refund-a", "7.00", 'c'),
                () -> reserveRefund(fixture, UUID.randomUUID(), "refund-b", "7.00", 'd'));

        assertThat(results).filteredOn(Result::successful).hasSize(1);
        assertThat(results).filteredOn(result -> result.failure() instanceof IllegalArgumentException).hasSize(1)
                .first().extracting(result -> result.failure().getMessage()).asString().contains("saldo reembolsable");
        assertThat(repository.findAll()).filteredOn(operation -> operation.getOperationType() == PaymentTerminalOperationType.REFUND)
                .singleElement().extracting(PaymentTerminalOperation::getAmount).isEqualTo(new BigDecimal("7.00"));
    }

    @Test
    void concurrentRefundAndVoidCreateOnlyOneAdjustment() throws Exception {
        var fixture = approvedCharge("10.00");
        var results = concurrently(
                () -> reserveRefund(fixture, UUID.randomUUID(), "refund", "4.00", 'e'),
                () -> reserveVoid(fixture, UUID.randomUUID(), "void", 'f'));

        assertThat(results).filteredOn(Result::successful).hasSize(1);
        assertThat(results).filteredOn(result -> result.failure() != null).hasSize(1);
        assertThat(repository.findAll()).filteredOn(operation -> operation.getOperationType() != PaymentTerminalOperationType.CHARGE)
                .hasSize(1);
    }

    private Fixture approvedCharge(String amount) {
        var company = UUID.randomUUID();
        var store = UUID.randomUUID();
        var terminal = UUID.randomUUID();
        var address = "{\"linea1\":\"a\",\"ciudad\":\"c\",\"codigoPostal\":\"1\",\"provincia\":\"p\",\"pais\":\"ES\"}";
        jdbc.update("insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values (?,?,?,cast(? as jsonb))", company, "B1", "C", address);
        jdbc.update("insert into tienda(id,empresa_id,nombre,direccion,address_normalized_hash,timezone,moneda,locale,codigo_tienda) values (?,?,?,cast(? as jsonb),?,?,?,?,?)",
                store, company, "S", address, "h", "UTC", "EUR", "es", "001");
        jdbc.update("insert into terminal(id,tienda_id,nombre,tipo,credential_hash) values (?,?,?,?,?)", terminal, store, "T", "TERMINAL_VENTA", "h");
        var charge = inTransaction(() -> {
            var operation = PaymentTerminalOperation.reserve(UUID.randomUUID(), terminal, store,
                    PaymentTerminalProvider.PAYTEF, PaymentTerminalMode.SIMULATED, PaymentTerminalOperationType.CHARGE,
                    null, UUID.randomUUID().toString(), "a".repeat(64), new BigDecimal(amount), "9".repeat(64), 1, NOW);
            operation.markSent("SENT", NOW.plusSeconds(1));
            operation.approve("REF", "AUTH", NOW.plusSeconds(2));
            return repository.saveAndFlush(operation);
        });
        return new Fixture(charge.getId(), terminal, store);
    }

    private PaymentTerminalOperation reserveRefund(Fixture fixture, UUID id, String key, String amount, char hash) {
        return inTransaction(() -> service.reserveRefund(id, fixture.chargeId(), fixture.terminalId(), fixture.storeId(),
                PaymentTerminalProvider.PAYTEF, key, String.valueOf(hash).repeat(64), new BigDecimal(amount),
                "9".repeat(64), 1, NOW.plusSeconds(3)));
    }

    private PaymentTerminalOperation reserveVoid(Fixture fixture, UUID id, String key, char hash) {
        return inTransaction(() -> service.reserveVoid(id, fixture.chargeId(), fixture.terminalId(), fixture.storeId(),
                PaymentTerminalProvider.PAYTEF, key, String.valueOf(hash).repeat(64), "9".repeat(64), 1,
                NOW.plusSeconds(3)));
    }

    @SafeVarargs
    private final ArrayList<Result> concurrently(Supplier<PaymentTerminalOperation>... actions) throws Exception {
        var start = new CountDownLatch(1);
        var results = new ArrayList<Result>();
        try (var executor = Executors.newFixedThreadPool(actions.length)) {
            var futures = new ArrayList<java.util.concurrent.Future<PaymentTerminalOperation>>();
            for (var action : actions) futures.add(executor.submit(() -> { start.await(); return action.get(); }));
            start.countDown();
            for (var future : futures) {
                try { results.add(new Result(future.get(), null)); }
                catch (ExecutionException exception) { results.add(new Result(null, rootCause(exception))); }
            }
        }
        return results;
    }

    private <T> T inTransaction(Supplier<T> action) { return new TransactionTemplate(transactionManager).execute(status -> action.get()); }

    private static Throwable rootCause(Throwable failure) {
        var result = failure;
        while (result.getCause() != null) result = result.getCause();
        return result;
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " no configurada");
        return value;
    }

    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD); var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private record Fixture(UUID chargeId, UUID terminalId, UUID storeId) {}
    private record Result(PaymentTerminalOperation operation, Throwable failure) {
        boolean successful() { return operation != null; }
    }
}
