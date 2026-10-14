package com.tpverp.backend.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import com.tpverp.backend.security.domain.UserAccountRepository;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class, ControlRuleService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class ControlRuleRetirementPostgreSqlTest {
    private static final String URL = System.getenv("TPV_ERP_TEST_DB_URL");
    private static final String USER = System.getenv("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "control_rule_retirement_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final UsernamePasswordAuthenticationToken AUTH =
            new UsernamePasswordAuthenticationToken("test", "test");

    @Autowired private ControlRuleService service;
    @Autowired private ControlRuleRepository rules;
    @Autowired private ControlRuleVersionRepository versions;
    @Autowired private UserAccountRepository users;
    @Autowired private ControlEventRepository events;
    @Autowired private ControlAlertRepository alerts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private CurrentOrganization organization;
    @MockitoBean private Clock clock;
    private UUID storeId;
    private UUID userId;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL + (URL.contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @BeforeEach
    void prepareStore() {
        var companyId = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        userId = UUID.randomUUID();
        String address = "{\"linea1\":\"x\",\"ciudad\":\"x\",\"codigoPostal\":\"1\",\"provincia\":\"x\",\"pais\":\"ES\"}";
        jdbc.update("insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values (?,?,?,cast(? as jsonb))",
                companyId, "B" + companyId.toString().substring(0, 8), "Retirement test", address);
        jdbc.update("""
                insert into tienda(id,empresa_id,nombre,direccion,address_normalized_hash,
                    timezone,moneda,locale,codigo_tienda)
                values (?,?,?,cast(? as jsonb),?,?,?,?,?)
                """, storeId, companyId, "Retirement test", address, storeId.toString(),
                "Atlantic/Canary", "EUR", "es-ES", "001");
        jdbc.update("insert into rol(id,tienda_id,nombre) values (?,?,?)", roleId, storeId, "TEST");
        jdbc.update("insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id) values (?,?,?,?,?,?)",
                userId, storeId, "TEST", userId.toString(), "test-only", roleId);
        var store = mock(Store.class);
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
        var user = users.findById(userId).orElseThrow();
        when(organization.currentUser(any())).thenReturn(user);
        when(clock.instant()).thenReturn(NOW);
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (!SCHEMA.matches("control_rule_retirement_[0-9a-f]{32}")) throw new IllegalStateException(SCHEMA);
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + SCHEMA + " cascade");
        }
    }

    @Test
    void retirementAndReadditionKeepIdentityEvidenceAndEveryVersion() {
        assertThat(jdbc.queryForObject("select max(version::integer) from flyway_schema_history "
                + "where success and version ~ '^[0-9]+$'", Integer.class)).isEqualTo(273);
        var created = create();
        var rule = rules.findById(created.id()).orElseThrow();
        var event = events.saveAndFlush(new ControlEvent(storeId, rule, "TEST", UUID.randomUUID(),
                null, "TEST-001", null, userId, "TEST", NOW, Map.of("reason", "immutable evidence")));
        var alert = alerts.saveAndFlush(new ControlAlert(event));
        var originalEvidence = jdbc.queryForObject("select row_to_json(event)::text from control_evento event where id=?",
                String.class, event.getId());

        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        service.retire(created.id(), created.version(), AUTH);

        assertThat(service.list()).isEmpty();
        assertThat(service.catalog()).filteredOn(item -> item.type() == created.type())
                .singleElement().satisfies(item -> assertThat(item.configured()).isFalse());
        assertThat(rules.findAllByStoreIdAndTypeAndActiveTrue(storeId, created.type())).isEmpty();
        assertThatThrownBy(() -> service.get(created.id())).isInstanceOf(NoSuchElementException.class);
        assertThat(service.versions(created.id())).extracting(ControlRuleService.RuleVersionView::retired)
                .containsExactly(true, false);
        var retired = rules.findById(created.id()).orElseThrow();
        assertThat(retired.getVersion()).isEqualTo(created.version() + 1);
        assertThat(retired.isRetired()).isTrue();
        assertThat(retired.isActive()).isFalse();
        assertThat(alerts.countByRuleAndStatus(storeId, NOW.minusSeconds(1), NOW.plusSeconds(10)))
                .singleElement().satisfies(count -> {
                    assertThat(count.getRuleId()).isEqualTo(created.id());
                    assertThat(count.getTotal()).isEqualTo(1);
                });

        when(clock.instant()).thenReturn(NOW.plusSeconds(2));
        var restored = create();
        assertThat(restored.id()).isEqualTo(created.id());
        assertThat(restored.ruleVersion()).isEqualTo(3);
        assertThat(restored.version()).isEqualTo(created.version() + 2);
        assertThat(restored.active()).isTrue();
        assertThat(service.list()).hasSize(1);
        assertThat(versions.findAllByRuleIdOrderByRuleVersionDesc(created.id()))
                .extracting(ControlRuleVersion::isRetired).containsExactly(false, true, false);
        assertThat(versions.findAllByRuleIdOrderByRuleVersionDesc(created.id()))
                .extracting(ControlRuleVersion::isActive).containsExactly(true, false, true);
        assertThat(jdbc.queryForObject("select row_to_json(event)::text from control_evento event where id=?",
                String.class, event.getId())).isEqualTo(originalEvidence);
        assertThat(alerts.findById(alert.getId())).isPresent();
        assertThatThrownBy(() -> service.update(created.id(), new ControlRuleService.UpdateRuleRequest(
                false, Map.of(), created.version()), AUTH)).isInstanceOf(IllegalStateException.class);
        assertThat(service.versions(created.id())).hasSize(3);
    }

    @Test
    void concurrentUpdateRollsBackRetirementAndDoesNotAppendItsSnapshot() {
        var created = create();
        var outer = new TransactionTemplate(transactionManager);
        var independent = new TransactionTemplate(transactionManager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            rules.findById(created.id()).orElseThrow(); // Keep the original entity in this persistence context.
            independent.executeWithoutResult(other -> service.update(created.id(),
                    new ControlRuleService.UpdateRuleRequest(true, Map.of(), created.version()), AUTH));
            service.retire(created.id(), created.version(), AUTH);
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("Conflicto de version");

        var current = service.get(created.id());
        assertThat(current.version()).isEqualTo(created.version() + 1);
        assertThat(current.ruleVersion()).isEqualTo(2);
        assertThat(current.active()).isTrue();
        assertThat(rules.findById(created.id()).orElseThrow().isRetired()).isFalse();
        assertThat(service.versions(created.id())).hasSize(2).allSatisfy(version -> {
            assertThat(version.retired()).isFalse();
            assertThat(version.active()).isTrue();
        });
    }

    @Test
    void databaseStillRejectsPhysicalDeletionAndInvalidRetirementStates() {
        var created = create();
        assertThatThrownBy(() -> jdbc.update("delete from control_regla where id=?", created.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update control_regla set retirada=true where id=?", created.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from control_regla_version where regla_id=?", created.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(service.get(created.id()).active()).isTrue();
        assertThat(service.versions(created.id())).hasSize(1);
    }

    private ControlRuleService.RuleView create() {
        return service.create(new ControlRuleService.CreateRuleRequest(
                ControlAlertType.TICKET_CANCELLED, true, Map.of()), AUTH);
    }
}
