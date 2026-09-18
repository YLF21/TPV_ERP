package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tpverp.backend.control.ControlAlertDetectionService;
import com.tpverp.backend.control.ControlAlertHistoryRepository;
import com.tpverp.backend.control.ControlAlertRepository;
import com.tpverp.backend.control.ControlAlertStatus;
import com.tpverp.backend.control.ControlAlertType;
import com.tpverp.backend.control.ControlEventRepository;
import com.tpverp.backend.control.ControlRule;
import com.tpverp.backend.control.ControlRuleRepository;
import com.tpverp.backend.control.ControlRuleVersion;
import com.tpverp.backend.control.ControlRuleVersionRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.shared.api.ApiExceptionHandler;
import com.tpverp.backend.terminal.CurrentTerminal;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Real JDBC persistence and the real JPA-backed detector; only identity and time are supplied. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class, SaleLineDeletionService.class, ControlAlertDetectionService.class})
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class SaleLineDeletionServicePostgreSqlTest {

    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "sale_deletion_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-09-17T01:14:20.123456Z");
    private static final TestingAuthenticationToken AUTH = new TestingAuthenticationToken("SELLER", "fixture-only");

    static { execute("create schema " + SCHEMA); }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private StoreRepository stores;
    @Autowired private UserAccountRepository users;
    @Autowired private ControlRuleRepository rules;
    @Autowired private ControlRuleVersionRepository versions;
    @Autowired private ControlEventRepository events;
    @Autowired private ControlAlertRepository alerts;
    @Autowired private ControlAlertHistoryRepository history;
    @Autowired private SaleLineDeletionService service;
    @MockitoBean private CurrentOrganization organization;
    @MockitoBean private CurrentTerminal currentTerminal;
    @MockitoBean private Clock clock;
    private UUID storeId;
    private UUID userId;
    private UUID terminalId;
    private UUID productId;
    private MockMvc mvc;

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
        mvc = MockMvcBuilders.standaloneSetup(new SaleLineDeletionController(service))
                .setControllerAdvice(new ApiExceptionHandler(new StaticMessageSource()))
                .build();
    }

    @Test
    void clearingTheCartPersistsTimestampsAndCreatesOneAlertAcrossAnIdempotentRetry() {
        saveRule(new ControlRule(storeId, ControlAlertType.SALE_SCREEN_CLEARED,
                true, Map.of(), userId, NOW));
        var saleOperation = UUID.randomUUID();
        var deletionOperation = UUID.randomUUID();

        var first = service.record(saleOperation, deletionOperation, List.of(line()), true, AUTH);
        flushAndClear();
        var retry = service.record(saleOperation, deletionOperation, List.of(line()), true, AUTH);
        flushAndClear();

        assertThat(first).singleElement().satisfies(row -> {
            assertThat(row.type()).isEqualTo("LISTA");
            assertThat(row.deletedAt()).isEqualTo(NOW);
            assertThat(row.unitPrice()).isEqualByComparingTo("3.125");
            assertThat(row.total()).isEqualByComparingTo("6.25");
        });
        assertThat(retry).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(first.getFirst().id());
            assertThat(row.deletedAt()).isEqualTo(NOW);
            assertThat(row.total()).isEqualByComparingTo("6.25");
        });
        assertThat(count("venta_operacion_eliminacion")).isEqualTo(1);
        assertThat(count("venta_linea_eliminada")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select eliminado_en from venta_operacion_eliminacion where id=?",
                Timestamp.class, deletionOperation).toInstant()).isEqualTo(NOW);
        assertThat(events.findAll()).singleElement().satisfies(event -> {
            assertThat(event.getType()).isEqualTo(ControlAlertType.SALE_SCREEN_CLEARED);
            assertThat(event.getSourceType()).isEqualTo("SALE_SCREEN");
            assertThat(event.getSourceId()).isEqualTo(deletionOperation);
            assertThat(event.getStoreId()).isEqualTo(storeId);
            assertThat(event.getUserId()).isEqualTo(userId);
            assertThat(event.getTerminalId()).isEqualTo(terminalId);
            assertThat(event.getOccurredAt()).isEqualTo(NOW);
            assertThat(event.getData()).containsEntry("lineCount", 1);
            assertThat(new BigDecimal(event.getData().get("total").toString())).isEqualByComparingTo("6.25");
        });
        assertThat(alerts.findAll()).singleElement().satisfies(alert ->
                assertThat(alert.getStatus()).isEqualTo(ControlAlertStatus.NEW));
        assertThat(history.count()).isEqualTo(1);
    }

    @Test
    void foreignStoreProductIsRejectedWith404BeforeRecordingAnyLines() throws Exception {
        var foreignProduct = foreignStoreProduct();
        var saleOperation = UUID.randomUUID();
        var deletionOperation = UUID.randomUUID();
        var body = """
                {"saleOperationId":"%s","deletionOperationId":"%s","fullTicketClear":true,"lines":[
                  {"productId":"%s","code":"","name":"Own product","quantity":1,"unitPrice":3.125},
                  {"productId":"%s","code":"","name":"Foreign product","quantity":-1,"unitPrice":3.125}
                ]}
                """.formatted(saleOperation, deletionOperation, productId, foreignProduct);

        mvc.perform(post("/api/v1/sale-line-deletions").principal(AUTH)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(count("venta_operacion_eliminacion")).isZero();
        assertThat(count("venta_linea_eliminada")).isZero();
        assertThat(events.count()).isZero();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void removingARefundLineWithoutCodePreservesTheSignedEvidenceAndDoesNotDuplicateAlerts(String code)
            throws Exception {
        jdbc.update("update producto set activo=false where id=?", productId);
        saveRule(new ControlRule(storeId, ControlAlertType.SALE_SCREEN_CLEARED,
                true, Map.of(), userId, NOW));
        var saleOperation = UUID.randomUUID();
        var deletionOperation = UUID.randomUUID();
        var body = """
                {"saleOperationId":"%s","deletionOperationId":"%s","fullTicketClear":true,"lines":[
                  {"productId":"%s","code":%s,"name":"Product","quantity":-1,"unitPrice":3.125}
                ]}
                """.formatted(saleOperation, deletionOperation, productId, code == null ? "null" : "\"\"");

        for (var attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/v1/sale-line-deletions").principal(AUTH)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].code").value(""))
                    .andExpect(jsonPath("$[0].quantity").value(-1))
                    .andExpect(jsonPath("$[0].unitPrice").value(3.125))
                    .andExpect(jsonPath("$[0].total").value(-3.13));
            flushAndClear();
        }

        assertThat(count("venta_operacion_eliminacion")).isEqualTo(1);
        assertThat(count("venta_linea_eliminada")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select cantidad from venta_linea_eliminada", Integer.class)).isEqualTo(-1);
        assertThat(events.findAll()).singleElement().satisfies(event -> {
            assertThat(new BigDecimal(event.getData().get("total").toString())).isEqualByComparingTo("-3.13");
            var lines = (List<?>) event.getData().get("lines");
            var line = (Map<?, ?>) lines.getFirst();
            assertThat(new BigDecimal(line.get("quantity").toString())).isEqualByComparingTo("-1");
            assertThat(line.get("code")).isEqualTo("");
        });
        assertThat(alerts.count()).isEqualTo(1);
        assertThat(history.count()).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void zeroQuantityIsRejectedWithoutPersistenceOutsideATestTransaction() {
        var deletionOperation = UUID.randomUUID();
        var zero = new SaleLineDeletionCommand(productId, "", "Product", 0, new BigDecimal("3.125"));

        assertThatThrownBy(() -> service.record(UUID.randomUUID(), deletionOperation, List.of(zero), true, AUTH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("cantidad no puede ser cero");

        assertThat(jdbc.queryForObject("select count(*) from venta_operacion_eliminacion where id=?",
                Integer.class, deletionOperation)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from venta_linea_eliminada where operacion_eliminacion_id=?",
                Integer.class, deletionOperation)).isZero();
        assertThat(events.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.125", "-0.125"})
    void fractionalDeferredLinesPreserveQuantityBothTimesAndEvidenceAcrossRetries(String quantity) throws Exception {
        var occurredAt = NOW.minus(400, ChronoUnit.DAYS);
        var deletionOperation = UUID.randomUUID();
        var body = deferredBody(UUID.randomUUID(), deletionOperation, occurredAt, context(), quantity);
        saveRule(new ControlRule(storeId, ControlAlertType.SALE_SCREEN_CLEARED,
                true, Map.of(), userId, occurredAt.minusSeconds(1)));

        mvc.perform(post("/api/v1/sale-line-deletions").principal(AUTH)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].quantity").value(Double.parseDouble(quantity)))
                .andExpect(jsonPath("$[0].deletedAt").value(occurredAt.toString()))
                .andExpect(jsonPath("$[0].receivedAt").value(NOW.toString()));
        flushAndClear();
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        mvc.perform(post("/api/v1/sale-line-deletions").principal(AUTH)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].receivedAt").value(NOW.toString()));
        flushAndClear();
        service.purgeExpired();

        assertThat(jdbc.queryForObject("select cantidad from venta_linea_eliminada", BigDecimal.class))
                .isEqualByComparingTo(quantity);
        assertThat(jdbc.queryForObject("select total from venta_linea_eliminada", BigDecimal.class))
                .isEqualByComparingTo(Money.euros(new BigDecimal(quantity).multiply(new BigDecimal("3.125"))));
        assertThat(jdbc.queryForObject("select eliminado_en from venta_operacion_eliminacion where id=?",
                Timestamp.class, deletionOperation).toInstant()).isEqualTo(occurredAt);
        assertThat(jdbc.queryForObject("select recibido_en from venta_operacion_eliminacion where id=?",
                Timestamp.class, deletionOperation).toInstant()).isEqualTo(NOW);
        assertThat(count("venta_operacion_eliminacion")).isEqualTo(1);
        assertThat(count("venta_linea_eliminada")).isEqualTo(1);
        assertThat(events.findAll()).singleElement().satisfies(event -> {
            assertThat(event.getData()).containsEntry("occurredAt", occurredAt.toString())
                    .containsEntry("receivedAt", NOW.toString());
            var evidence = (Map<?, ?>) ((List<?>) event.getData().get("lines")).getFirst();
            assertThat(new BigDecimal(evidence.get("quantity").toString())).isEqualByComparingTo(quantity);
        });
        assertThat(alerts.count()).isEqualTo(1);
        assertThat(history.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"store", "user", "terminal"})
    void rejectsManipulatedContextBeforeAnyPersistence(String changed) throws Exception {
        var original = context();
        var altered = new SaleLineDeletionContext(
                changed.equals("store") ? UUID.randomUUID() : original.storeId(),
                changed.equals("user") ? UUID.randomUUID() : original.userId(),
                changed.equals("terminal") ? UUID.randomUUID() : original.terminalId());
        var body = deferredBody(UUID.randomUUID(), UUID.randomUUID(), NOW.minusSeconds(60), altered, "1");

        mvc.perform(post("/api/v1/sale-line-deletions").principal(AUTH)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        assertThat(count("venta_operacion_eliminacion")).isZero();
        assertThat(count("venta_linea_eliminada")).isZero();
        assertThat(events.count()).isZero();
    }

    @Test
    void requiresContextAndOccurrenceTogetherAndRejectsFutureOrExcessPrecisionBeforeWriting() throws Exception {
        var valid = deferredBody(UUID.randomUUID(), UUID.randomUUID(), NOW, context(), "1");
        var missingContext = valid.replaceFirst("\"context\":\\{[^}]+\\},", "");
        var missingTime = valid.replaceFirst("\"occurredAt\":\"[^\"]+\",", "");
        var future = valid.replace(NOW.toString(), NOW.plusSeconds(2).toString());
        var excessivePrecision = deferredBody(UUID.randomUUID(), UUID.randomUUID(), NOW, context(), "0.0001");

        for (var body : List.of(missingContext, missingTime, future, excessivePrecision)) {
            mvc.perform(post("/api/v1/sale-line-deletions").principal(AUTH)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(count("venta_operacion_eliminacion")).isZero();
        assertThat(count("venta_linea_eliminada")).isZero();
    }

    @Test
    void acceptsSubSecondTimeRoundingWithoutChangingTheOriginalTime() {
        var original = NOW.plusMillis(500).plusNanos(123);
        var result = service.record(UUID.randomUUID(), UUID.randomUUID(), List.of(line()), false,
                original, context(), AUTH);

        assertThat(result.getFirst().deletedAt()).isEqualTo(original.truncatedTo(ChronoUnit.MICROS));
        assertThat(result.getFirst().receivedAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsReusedOperationWithChangedPayloadOrOccurrenceWithoutReplacingEvidence() throws Exception {
        var occurredAt = NOW.minusSeconds(60);
        var operation = UUID.randomUUID();
        var sale = UUID.randomUUID();
        var valid = deferredBody(sale, operation, occurredAt, context(), "0.125");
        mvc.perform(post("/api/v1/sale-line-deletions").principal(AUTH)
                        .contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isOk());

        for (var changed : List.of(deferredBody(sale, operation, occurredAt, context(), "0.250"),
                deferredBody(sale, operation, occurredAt.minusSeconds(1), context(), "0.125"))) {
            mvc.perform(post("/api/v1/sale-line-deletions").principal(AUTH)
                            .contentType(MediaType.APPLICATION_JSON).content(changed))
                    .andExpect(status().isConflict());
        }
        assertThat(count("venta_operacion_eliminacion")).isEqualTo(1);
        assertThat(count("venta_linea_eliminada")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select cantidad from venta_linea_eliminada", BigDecimal.class))
                .isEqualByComparingTo("0.125");
    }

    private SaleLineDeletionContext context() {
        return new SaleLineDeletionContext(storeId, userId, terminalId);
    }

    private void saveRule(ControlRule rule) {
        versions.saveAndFlush(new ControlRuleVersion(rules.saveAndFlush(rule)));
    }

    private String deferredBody(UUID sale, UUID operation, Instant occurredAt,
            SaleLineDeletionContext context, String quantity) {
        return """
                {"saleOperationId":"%s","deletionOperationId":"%s","fullTicketClear":true,
                 "occurredAt":"%s","context":{"storeId":"%s","userId":"%s","terminalId":"%s"},
                 "lines":[{"productId":"%s","code":"","name":"Product","quantity":%s,"unitPrice":3.125}]}
                """.formatted(sale, operation, occurredAt, context.storeId(), context.userId(),
                context.terminalId(), productId, quantity);
    }

    @Test
    void purgeRemovesExpiredLinesAndHeadersWhileKeepingTheExactRetentionBoundary() {
        var cutoff = NOW.minus(365, ChronoUnit.DAYS);
        when(clock.instant()).thenReturn(cutoff.minus(1, ChronoUnit.MICROS));
        var expired = UUID.randomUUID();
        service.record(UUID.randomUUID(), expired, List.of(line()), false, AUTH);
        when(clock.instant()).thenReturn(cutoff);
        var boundary = UUID.randomUUID();
        service.record(UUID.randomUUID(), boundary, List.of(line()), false, AUTH);
        when(clock.instant()).thenReturn(NOW);
        var recent = UUID.randomUUID();
        service.record(UUID.randomUUID(), recent, List.of(line()), false, AUTH);

        service.purgeExpired();

        assertThat(jdbc.queryForList("select id from venta_operacion_eliminacion", UUID.class))
                .containsExactlyInAnyOrder(boundary, recent);
        assertThat(jdbc.queryForList("select operacion_eliminacion_id from venta_linea_eliminada", UUID.class))
                .containsExactlyInAnyOrder(boundary, recent);
    }

    private SaleLineDeletionCommand line() {
        return new SaleLineDeletionCommand(productId, "P001", "Product", 2, new BigDecimal("3.125"));
    }

    private UUID foreignStoreProduct() {
        var foreignStoreId = UUID.randomUUID();
        var foreignFamilyId = UUID.randomUUID();
        var foreignTaxId = UUID.randomUUID();
        var foreignProductId = UUID.randomUUID();
        jdbc.update("""
                insert into tienda(id,empresa_id,codigo_tienda,nombre,direccion,address_normalized_hash,timezone,moneda,locale)
                select ?,empresa_id,'002','Other store',direccion,'foreign-hash',timezone,moneda,locale
                from tienda where id=?
                """, foreignStoreId, storeId);
        jdbc.update("insert into familia(id,tienda_id,nombre) values(?,?,'FOREIGN')", foreignFamilyId, foreignStoreId);
        jdbc.update("insert into impuesto_tienda(id,tienda_id,porcentaje) values(?,?,0)", foreignTaxId, foreignStoreId);
        jdbc.update("insert into producto(id,tienda_id,familia_id,impuesto_id,nombre) values(?,?,?,?,'Foreign')",
                foreignProductId, foreignStoreId, foreignFamilyId, foreignTaxId);
        return foreignProductId;
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

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
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
