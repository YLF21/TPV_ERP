package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.tpverp.backend.control.ControlAlertDetectionService;
import com.tpverp.backend.control.ControlAlertType;
import com.tpverp.backend.control.ControlEvent;
import com.tpverp.backend.control.ControlEventRepository;
import com.tpverp.backend.control.ControlRule;
import com.tpverp.backend.control.ControlRuleRepository;
import com.tpverp.backend.control.ControlRuleVersion;
import com.tpverp.backend.control.ControlRuleVersionRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Each service call commits independently, including retries and concurrent delivery workers. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class, SaleLineDeletionService.class, ControlAlertDetectionService.class})
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SaleLineDeletionHistoricalRulesPostgreSqlTest {

    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "sale_deletion_history_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-09-17T10:00:00.123456Z");
    private static final Instant T1 = NOW.minusSeconds(300);
    private static final Instant T2 = NOW.minusSeconds(200);
    private static final Instant T3 = NOW.minusSeconds(100);
    private static final TestingAuthenticationToken AUTH = new TestingAuthenticationToken("SELLER", "fixture-only");

    static { execute("create schema " + SCHEMA); }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private StoreRepository stores;
    @Autowired private UserAccountRepository users;
    @Autowired private ControlRuleRepository rules;
    @Autowired private ControlRuleVersionRepository versions;
    @Autowired private ControlEventRepository events;
    @Autowired private SaleLineDeletionService service;
    @MockitoBean private CurrentOrganization organization;
    @MockitoBean private CurrentTerminal currentTerminal;
    @MockitoBean private Clock clock;
    private UUID storeId;
    private UUID userId;
    private UUID terminalId;
    private UUID productId;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL
                + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @AfterAll
    static void dropSchema() { execute("drop schema if exists " + SCHEMA + " cascade"); }

    @BeforeEach
    void setUp() {
        fixture();
        when(organization.currentStore()).thenReturn(stores.findWithCompanyById(storeId).orElseThrow());
        when(organization.currentUser(any())).thenReturn(users.findById(userId).orElseThrow());
        when(currentTerminal.terminalId(any())).thenReturn(terminalId);
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    void lateMiddleDeletionReconstructsTheOldThresholdAndUsesReceptionForAlertHistory() {
        var rule = saveRule(ControlAlertType.CONSECUTIVE_LINE_DELETIONS, true,
                Map.of("minimumCount", 3), T1.minusSeconds(1));
        rule = change(rule, true, Map.of("minimumCount", 5), T3.plusSeconds(1));
        var saleId = UUID.randomUUID();

        record(saleId, UUID.randomUUID(), T3, false);
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        record(saleId, UUID.randomUUID(), T1, false);
        assertThat(storeEvents()).isEmpty();
        when(clock.instant()).thenReturn(NOW.plusSeconds(2));
        record(saleId, UUID.randomUUID(), T2, false);

        var event = onlyEvent(saleId);
        assertThat(event.getRuleId()).isEqualTo(rule.getId());
        assertThat(event.getRuleVersion()).isEqualTo(1);
        assertThat(event.getOccurredAt()).isEqualTo(T3);
        assertThat(event.getSourceType()).isEqualTo("SALE_LINE_DELETION_SEQUENCE");
        assertThat(event.getData()).containsEntry("minimumCount", 3).containsEntry("deletionCount", 3);
        assertThat(evidenceLines(event)).hasSize(3);
        assertThat(evidenceLines(event).stream().map(line -> line.get("deletedAt").toString()))
                .containsExactly(T1.toString(), T2.toString(), T3.toString());
        assertThat(evidenceLines(event).stream().map(line -> line.get("receivedAt").toString()))
                .containsExactly(NOW.plusSeconds(1).toString(), NOW.plusSeconds(2).toString(), NOW.toString());
        assertAlertCreatedAt(event, NOW.plusSeconds(2));
        assertThat(rules.findById(rule.getId()).orElseThrow().getRuleVersion()).isEqualTo(2);
        assertThat(count("venta_operacion_eliminacion")).isEqualTo(3);
    }

    @Test
    void ruleActiveAtClearStillAppliesAfterItIsDisabled() {
        var rule = saveRule(ControlAlertType.SALE_SCREEN_CLEARED, true, Map.of(), T1.minusSeconds(1));
        change(rule, false, Map.of(), T1.plusSeconds(1));
        var deletionId = UUID.randomUUID();

        record(UUID.randomUUID(), deletionId, T1, true);

        var event = onlyEvent(deletionId);
        assertThat(event.getRuleVersion()).isEqualTo(1);
        assertThat(event.getOccurredAt()).isEqualTo(T1);
        assertThat(event.getType()).isEqualTo(ControlAlertType.SALE_SCREEN_CLEARED);
        assertThat(event.getData()).containsEntry("occurredAt", T1.toString())
                .containsEntry("receivedAt", NOW.toString());
        assertThat(evidenceLines(event)).singleElement().satisfies(line -> {
            assertThat(new BigDecimal(line.get("quantity").toString())).isEqualByComparingTo("0.125");
            assertThat(new BigDecimal(line.get("unitPrice").toString())).isEqualByComparingTo("3.125");
            assertThat(new BigDecimal(line.get("total").toString())).isEqualByComparingTo("0.39");
        });
        assertAlertCreatedAt(event, NOW);
    }

    @Test
    void subsequentlyEnabledRuleDoesNotRetroactivelyAlertAnInactiveClear() {
        var rule = saveRule(ControlAlertType.SALE_SCREEN_CLEARED, false, Map.of(), T1.minusSeconds(1));
        change(rule, true, Map.of(), T1.plusSeconds(1));

        record(UUID.randomUUID(), UUID.randomUUID(), T1, true);

        assertNoAlertAndOneRecordedDeletion();
    }

    @Test
    void ruleCreatedAfterDeletionDoesNotApplyRetroactively() {
        saveRule(ControlAlertType.SALE_SCREEN_CLEARED, true, Map.of(), T1.plusSeconds(1));

        record(UUID.randomUUID(), UUID.randomUUID(), T1, true);

        assertNoAlertAndOneRecordedDeletion();
    }

    @Test
    void missingRuleHistoryDoesNotFallBackToTheMutableCurrentRule() {
        var rule = rules.saveAndFlush(new ControlRule(storeId, ControlAlertType.SALE_SCREEN_CLEARED,
                true, Map.of(), userId, T1.minusSeconds(1)));
        assertThat(versions.findAllByRuleIdOrderByRuleVersionDesc(rule.getId())).isEmpty();

        record(UUID.randomUUID(), UUID.randomUUID(), T1, true);

        assertNoAlertAndOneRecordedDeletion();
    }

    @Test
    void retryAfterRuleChangesPreservesTheOriginalEvidenceAndAuditHistory() {
        var rule = saveRule(ControlAlertType.SALE_SCREEN_CLEARED, true, Map.of(), T1.minusSeconds(1));
        var saleId = UUID.randomUUID();
        var deletionId = UUID.randomUUID();
        var first = record(saleId, deletionId, T1, true);
        var original = onlyEvent(deletionId);
        rule = change(rule, false, Map.of(), NOW.plusSeconds(1));
        change(rule, true, Map.of(), NOW.plusSeconds(2));
        when(clock.instant()).thenReturn(NOW.plusSeconds(3));

        var retry = record(saleId, deletionId, T1, true);

        assertThat(retry).isEqualTo(first);
        var event = onlyEvent(deletionId);
        assertThat(event.getId()).isEqualTo(original.getId());
        assertThat(event.getRuleVersion()).isEqualTo(1);
        assertThat(event.getData()).isEqualTo(original.getData());
        assertAlertCreatedAt(event, NOW);
        assertThat(count("control_alerta_historial")).isEqualTo(1);
        assertThat(count("venta_operacion_eliminacion")).isEqualTo(1);
        assertThat(count("venta_linea_eliminada")).isEqualTo(1);
    }

    @Test
    void lateDeletionReevaluatesAnEarlierActivePointEvenWhenTheLatestPointWasDisabled() {
        var rule = saveRule(ControlAlertType.CONSECUTIVE_LINE_DELETIONS, true,
                Map.of("minimumCount", 2), T1.minusSeconds(1));
        change(rule, false, Map.of("minimumCount", 2), T2.plusSeconds(1));
        var saleId = UUID.randomUUID();

        record(saleId, UUID.randomUUID(), T2, false);
        record(saleId, UUID.randomUUID(), T3, false);
        assertThat(storeEvents()).isEmpty();
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        record(saleId, UUID.randomUUID(), T1, false);

        var event = onlyEvent(saleId);
        assertThat(event.getOccurredAt()).isEqualTo(T2);
        assertThat(event.getRuleVersion()).isEqualTo(1);
        assertThat(event.getData()).containsEntry("deletionCount", 2);
        assertThat(evidenceLines(event).stream().map(line -> line.get("deletedAt").toString()))
                .containsExactly(T1.toString(), T2.toString());
        assertAlertCreatedAt(event, NOW.plusSeconds(1));
    }

    @Test
    void laterDeletionsCannotSatisfyAnEarlierLowerThreshold() {
        var rule = saveRule(ControlAlertType.CONSECUTIVE_LINE_DELETIONS, true,
                Map.of("minimumCount", 2), T1.minusSeconds(1));
        change(rule, true, Map.of("minimumCount", 999), T1.plusSeconds(1));
        var saleId = UUID.randomUUID();

        record(saleId, UUID.randomUUID(), T3, false);
        record(saleId, UUID.randomUUID(), T2, false);
        record(saleId, UUID.randomUUID(), T1, false);

        assertThat(storeEvents()).isEmpty();
        assertThat(count("venta_operacion_eliminacion")).isEqualTo(3);
        assertThat(count("control_alerta")).isZero();
    }

    @Test
    void changingSaleOperationIdKeepsTheDeletionSequencesSeparate() {
        saveRule(ControlAlertType.CONSECUTIVE_LINE_DELETIONS, true,
                Map.of("minimumCount", 2), T1.minusSeconds(1));
        var firstSale = UUID.randomUUID();
        var resetSale = UUID.randomUUID();

        record(firstSale, UUID.randomUUID(), T1, false);
        record(resetSale, UUID.randomUUID(), T2, false);
        assertThat(storeEvents()).isEmpty();
        record(firstSale, UUID.randomUUID(), T3, false);

        var event = onlyEvent(firstSale);
        assertThat(event.getData()).containsEntry("deletionCount", 2);
        assertThat(evidenceLines(event).stream().map(line -> line.get("deletedAt").toString()))
                .containsExactly(T1.toString(), T3.toString());
        assertThat(storeEvents()).hasSize(1);
        assertThat(count("venta_operacion_eliminacion")).isEqualTo(3);
    }

    @Test
    void simultaneousRetriesOfTheSameUuidCommitOneDeletionAndOneAuditTrail() throws Exception {
        saveRule(ControlAlertType.SALE_SCREEN_CLEARED, true, Map.of(), T1.minusSeconds(1));

        for (var round = 0; round < 3; round++) {
            var saleId = UUID.randomUUID();
            var deletionId = UUID.randomUUID();
            var calls = new ArrayList<Callable<List<SaleLineDeletionView>>>();
            for (var worker = 0; worker < 8; worker++) {
                calls.add(() -> record(saleId, deletionId, T1, true));
            }

            var responses = concurrently(calls);

            assertThat(responses).allSatisfy(response -> assertThat(response).isEqualTo(responses.getFirst()));
            assertThat(jdbc.queryForObject("select count(*) from venta_operacion_eliminacion where id=?",
                    Integer.class, deletionId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from venta_linea_eliminada where operacion_eliminacion_id=?",
                    Integer.class, deletionId)).isEqualTo(1);
            assertAlertCreatedAt(onlyEvent(deletionId), NOW);
        }
        assertThat(count("control_evento")).isEqualTo(3);
        assertThat(count("control_alerta")).isEqualTo(3);
        assertThat(count("control_alerta_historial")).isEqualTo(3);
    }

    @Test
    void differentDeletionUuidsDeliveredTogetherCannotMissTheThreshold() throws Exception {
        saveRule(ControlAlertType.CONSECUTIVE_LINE_DELETIONS, true,
                Map.of("minimumCount", 2), T1.minusSeconds(1));

        for (var round = 0; round < 3; round++) {
            var saleId = UUID.randomUUID();
            var firstId = UUID.randomUUID();
            var secondId = UUID.randomUUID();

            concurrently(List.of(
                    () -> record(saleId, firstId, T1, false),
                    () -> record(saleId, secondId, T2, false)));

            var event = onlyEvent(saleId);
            assertThat(event.getOccurredAt()).isEqualTo(T2);
            assertThat(event.getRuleVersion()).isEqualTo(1);
            assertThat(event.getData()).containsEntry("deletionCount", 2).containsEntry("minimumCount", 2);
            assertThat(evidenceLines(event)).hasSize(2);
            assertAlertCreatedAt(event, NOW);
        }
        assertThat(count("venta_operacion_eliminacion")).isEqualTo(6);
        assertThat(count("venta_linea_eliminada")).isEqualTo(6);
        assertThat(count("control_evento")).isEqualTo(3);
        assertThat(count("control_alerta_historial")).isEqualTo(3);
    }

    private ControlRule saveRule(ControlAlertType type, boolean active, Map<String, Object> configuration,
            Instant effectiveAt) {
        return saveVersion(new ControlRule(storeId, type, active, configuration, userId, effectiveAt));
    }

    private ControlRule change(ControlRule rule, boolean active, Map<String, Object> configuration,
            Instant effectiveAt) {
        rule.update(active, configuration, userId, effectiveAt);
        return saveVersion(rule);
    }

    private ControlRule saveVersion(ControlRule rule) {
        var saved = rules.saveAndFlush(rule);
        versions.saveAndFlush(new ControlRuleVersion(saved));
        return saved;
    }

    private List<SaleLineDeletionView> record(UUID saleId, UUID deletionId, Instant occurredAt, boolean fullClear) {
        var line = new SaleLineDeletionCommand(productId, "P001", "Product",
                new BigDecimal("0.125"), new BigDecimal("3.125"));
        return service.record(saleId, deletionId, List.of(line), fullClear, occurredAt,
                new SaleLineDeletionContext(storeId, userId, terminalId), AUTH);
    }

    private List<ControlEvent> storeEvents() {
        return jdbc.queryForList("select id from control_evento where tienda_id=?", UUID.class, storeId)
                .stream().map(id -> events.findById(id).orElseThrow()).toList();
    }

    private ControlEvent onlyEvent(UUID sourceId) {
        var matching = storeEvents().stream().filter(event -> event.getSourceId().equals(sourceId)).toList();
        assertThat(matching).hasSize(1);
        return matching.getFirst();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> evidenceLines(ControlEvent event) {
        return (List<Map<String, Object>>) event.getData().get("lines");
    }

    private void assertNoAlertAndOneRecordedDeletion() {
        assertThat(storeEvents()).isEmpty();
        assertThat(count("control_alerta")).isZero();
        assertThat(count("control_alerta_historial")).isZero();
        assertThat(count("venta_operacion_eliminacion")).isEqualTo(1);
        assertThat(count("venta_linea_eliminada")).isEqualTo(1);
    }

    private void assertAlertCreatedAt(ControlEvent event, Instant expected) {
        var alertId = jdbc.queryForObject("select id from control_alerta where evento_id=?", UUID.class, event.getId());
        assertThat(jdbc.queryForObject("select estado from control_alerta where id=?", String.class, alertId))
                .isEqualTo("NEW");
        assertThat(jdbc.queryForObject("select creada_en from control_alerta where id=?", Timestamp.class, alertId)
                .toInstant()).isEqualTo(expected);
        assertThat(jdbc.queryForObject("select actualizada_en from control_alerta where id=?", Timestamp.class, alertId)
                .toInstant()).isEqualTo(expected);
        assertThat(jdbc.queryForList("select cambiado_en from control_alerta_historial where alerta_id=?",
                Timestamp.class, alertId)).containsExactly(Timestamp.from(expected));
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where tienda_id=?", Integer.class, storeId);
    }

    private static List<List<SaleLineDeletionView>> concurrently(
            List<Callable<List<SaleLineDeletionView>>> calls) throws Exception {
        var barrier = new CyclicBarrier(calls.size());
        var executor = Executors.newFixedThreadPool(calls.size());
        try {
            var futures = new ArrayList<Future<List<SaleLineDeletionView>>>();
            for (var call : calls) {
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return call.call();
                }));
            }
            var results = new ArrayList<List<SaleLineDeletionView>>();
            for (var future : futures) results.add(future.get(45, TimeUnit.SECONDS));
            return results;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void fixture() {
        var companyId = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        var familyId = UUID.randomUUID();
        var taxId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        userId = UUID.randomUUID();
        terminalId = UUID.randomUUID();
        productId = UUID.randomUUID();
        var address = "{\"linea1\":\"x\",\"ciudad\":\"x\",\"codigoPostal\":\"1\",\"provincia\":\"x\",\"pais\":\"ES\"}";
        jdbc.update("insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values(?,'B00000001','Test company',cast(? as jsonb))",
                companyId, address);
        jdbc.update("""
                insert into tienda(id,empresa_id,codigo_tienda,nombre,direccion,address_normalized_hash,timezone,moneda,locale)
                values(?,?,'001','Test store',cast(? as jsonb),'hash','Atlantic/Canary','EUR','es-ES')
                """, storeId, companyId, address);
        jdbc.update("insert into rol(id,tienda_id,nombre) values(?,?,'SELLER')", roleId, storeId);
        jdbc.update("insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id) values(?,?,'SELLER','Seller','fixture-only',?)",
                userId, storeId, roleId);
        jdbc.update("insert into terminal(id,tienda_id,nombre,tipo,credential_hash) values(?,?,'TEST','TERMINAL_VENTA','fixture-only')",
                terminalId, storeId);
        jdbc.update("insert into familia(id,tienda_id,nombre) values(?,?,'TEST')", familyId, storeId);
        jdbc.update("insert into impuesto_tienda(id,tienda_id,porcentaje) values(?,?,0)", taxId, storeId);
        jdbc.update("insert into producto(id,tienda_id,familia_id,impuesto_id,nombre) values(?,?,?,?,'Product')",
                productId, storeId, familyId, taxId);
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
