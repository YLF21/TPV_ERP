package com.tpverp.backend.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import com.tpverp.backend.security.domain.UserAccountRepository;
import jakarta.persistence.EntityManager;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FlywayPostgreSqlConfiguration.class)
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class DashboardPreferencePostgreSqlTest {

    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "dashboard_preference_"
            + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-07-18T00:30:00Z");
    private static final Instant LEGACY_CREATED = NOW.minusSeconds(120);
    private static final Instant LEGACY_UPDATED = NOW.minusSeconds(60);
    private static final UUID LEGACY_USER;
    private static final List<DashboardWidgetLayout> LEGACY_WIDGETS = List.of(
            new DashboardWidgetLayout("sales.top-products", 12, 3),
            new DashboardWidgetLayout("sales.today", 3, 2));

    static {
        execute("create schema " + SCHEMA);
        try {
            // Seed a real pre-upgrade row. Spring Boot applies V246 before JPA starts.
            FlywayPostgreSqlConfiguration.disableTransactionalLock(Flyway.configure())
                    .dataSource(schemaUrl(), USER, PASSWORD)
                    .schemas(SCHEMA).defaultSchema(SCHEMA).target("245")
                    .load().migrate();
            var legacyJdbc = new JdbcTemplate(new DriverManagerDataSource(schemaUrl(), USER, PASSWORD));
            var legacyStore = storeFixture(legacyJdbc, "990");
            LEGACY_USER = userFixture(legacyJdbc, legacyStore, "LEGACY");
            legacyJdbc.update("""
                    insert into preferencia_dashboard(id,usuario_id,widgets,created_at,updated_at,version)
                    values (?,?,cast(? as jsonb),?,?,3)
                    """, UUID.randomUUID(), LEGACY_USER,
                    "[{\"key\":\"sales.top-products\",\"width\":12,\"height\":3},"
                            + "{\"key\":\"sales.today\",\"width\":3,\"height\":2}]",
                    Timestamp.from(LEGACY_CREATED), Timestamp.from(LEGACY_UPDATED));
        } catch (RuntimeException exception) {
            execute("drop schema if exists " + SCHEMA + " cascade");
            throw exception;
        }
    }

    @Autowired private DashboardPreferenceRepository preferences;
    @Autowired private UserAccountRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DashboardPreferencePostgreSqlTest::schemaUrl);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @AfterAll
    static void dropSchema() {
        execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @Test
    void upgradesExistingLayoutsToV246WithoutChangingOrderDimensionsOrTimestamps() {
        assertThat(jdbc.queryForObject("""
                select success from flyway_schema_history where version='246'
                """, Boolean.class)).isTrue();
        var legacy = preferences.findByUser(users.findById(LEGACY_USER).orElseThrow()).orElseThrow();

        assertThat(legacy.getWidgets()).containsExactlyElementsOf(LEGACY_WIDGETS);
        assertThat(legacy.getOptions()).isEqualTo(DashboardOptions.defaults());
        assertThat(legacy.getCreatedAt()).isEqualTo(LEGACY_CREATED);
        assertThat(legacy.getUpdatedAt()).isEqualTo(LEGACY_UPDATED);
        assertThat(jdbc.queryForObject("select version from preferencia_dashboard where usuario_id=?",
                Long.class, LEGACY_USER)).isEqualTo(3);
    }

    @Test
    void returnsDefaultsAndTheCurrentStoreBusinessDateWithoutWritingOnRead() {
        var store = storeFixture(jdbc, "001");
        var userId = userFixture(jdbc, store, "A");
        var authentication = authentication("GESTION_VENTAS");

        var honolulu = service(userId, authentication, "Pacific/Honolulu", NOW).get(authentication);
        var tokyo = service(userId, authentication, "Asia/Tokyo", NOW).get(authentication);

        assertThat(honolulu.options()).isEqualTo(DashboardOptions.defaults());
        assertThat(honolulu.widgets()).extracting(DashboardWidgetLayout::key)
                .containsExactly("sales.today", "sales.operations", "sales.average", "sales.units", "sales.trend", "sales.families", "sales.top-products",
                        "sales.hourly", "sales.corrections", "sales.payments");
        assertThat(honolulu.businessDate()).isEqualTo(LocalDate.of(2026, 7, 17));
        assertThat(honolulu.storeTimezone()).isEqualTo("Pacific/Honolulu");
        assertThat(tokyo.businessDate()).isEqualTo(LocalDate.of(2026, 7, 18));
        assertThat(tokyo.storeTimezone()).isEqualTo("Asia/Tokyo");
        assertThat(preferenceCount(userId)).isZero();
    }

    @Test
    void persistsIndependentUsersOptionsAndReloadsThemAcrossServiceAndPersistenceContexts() {
        var store = storeFixture(jdbc, "001");
        var userA = userFixture(jdbc, store, "A");
        var userB = userFixture(jdbc, store, "B");
        var authA = authentication("GESTION_VENTAS");
        var authB = authentication("GESTION_VENTAS");
        var layoutA = List.of(new DashboardWidgetLayout("sales.trend", 12, 3));
        var layoutB = List.of(new DashboardWidgetLayout("sales.top-products", 8, 2));
        var optionsA = new DashboardOptions("LAST_7_DAYS", "BAR", "TABLE", "COMPACT", false,
                "TABLE", "AMOUNT", "BAR", "BAR");
        var optionsB = new DashboardOptions("TODAY", "TABLE", "BAR", "COMFORTABLE", true);

        service(userA, authA, "Atlantic/Canary", NOW)
                .save(new DashboardPreferenceService.SavePreferenceRequest(layoutA, optionsA), authA);
        service(userB, authB, "Atlantic/Canary", NOW)
                .save(new DashboardPreferenceService.SavePreferenceRequest(layoutB, optionsB), authB);
        flushAndClear();

        var loadedA = service(userA, authA, "Atlantic/Canary", NOW).get(authA);
        var loadedB = service(userB, authB, "Atlantic/Canary", NOW).get(authB);
        assertThat(loadedA.widgets()).containsExactlyElementsOf(layoutA);
        assertThat(loadedA.options()).isEqualTo(optionsA);
        assertThat(loadedB.widgets()).containsExactlyElementsOf(layoutB);
        assertThat(loadedB.options()).isEqualTo(optionsB);

        var later = NOW.plusSeconds(60);
        var resizedA = List.of(new DashboardWidgetLayout("sales.trend", 8, 2));
        // An older client may still PUT only widgets; it must retain the saved options.
        service(userA, authA, "Europe/Madrid", later)
                .save(new DashboardPreferenceService.SavePreferenceRequest(resizedA), authA);
        flushAndClear();

        var updatedA = service(userA, authA, "Europe/Madrid", later).get(authA);
        var unchangedB = service(userB, authB, "Atlantic/Canary", later).get(authB);
        assertThat(updatedA.widgets()).containsExactlyElementsOf(resizedA);
        assertThat(updatedA.options()).isEqualTo(optionsA);
        assertThat(updatedA.storeTimezone()).isEqualTo("Europe/Madrid");
        assertThat(unchangedB.widgets()).containsExactlyElementsOf(layoutB);
        assertThat(unchangedB.options()).isEqualTo(optionsB);
        assertThat(preferenceCount(userA)).isEqualTo(1);
        assertThat(preferenceCount(userB)).isEqualTo(1);
        var rowA = preferences.findByUser(users.findById(userA).orElseThrow()).orElseThrow();
        var rowB = preferences.findByUser(users.findById(userB).orElseThrow()).orElseThrow();
        assertThat(rowA.getCreatedAt()).isEqualTo(NOW);
        assertThat(rowA.getUpdatedAt()).isEqualTo(later);
        assertThat(rowB.getCreatedAt()).isEqualTo(NOW);
        assertThat(rowB.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void retainsHiddenWidgetsDuringRestrictedSavesAndRestoresThemWhenPermissionsReturn() {
        var store = storeFixture(jdbc, "001");
        var userId = userFixture(jdbc, store, "A");
        var fullAuth = authentication("GESTION_VENTAS", "GESTION_PRODUCTO", "CONTROL_ALERTS_READ");
        var salesAuth = authentication("GESTION_VENTAS");
        var sales = new DashboardWidgetLayout("sales.today", 4, 1);
        var promotion = new DashboardWidgetLayout("promotions.active", 8, 3);
        var alerts = new DashboardWidgetLayout("control.alerts", 6, 2);
        var options = new DashboardOptions("LAST_30_DAYS", "LINE", "TABLE", "COMPACT", true);
        service(userId, fullAuth, "Atlantic/Canary", NOW).save(
                new DashboardPreferenceService.SavePreferenceRequest(List.of(sales, promotion, alerts), options), fullAuth);
        flushAndClear();
        var limitedService = service(userId, salesAuth, "Atlantic/Canary", NOW.plusSeconds(1));

        assertThat(limitedService.get(salesAuth).widgets()).containsExactly(sales);
        assertThat(limitedService.get(salesAuth).availableWidgets())
                .doesNotContain("promotions.active", "control.alerts");
        assertThatThrownBy(() -> limitedService.save(
                new DashboardPreferenceService.SavePreferenceRequest(List.of(promotion)), salesAuth))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("widgets no permitidos");
        var resized = new DashboardWidgetLayout("sales.today", 12, 2);
        var saved = limitedService.save(
                new DashboardPreferenceService.SavePreferenceRequest(List.of(resized)), salesAuth);
        assertThat(saved.widgets()).containsExactly(resized);
        flushAndClear();

        var restored = service(userId, fullAuth, "Atlantic/Canary", NOW.plusSeconds(2)).get(fullAuth);
        assertThat(restored.widgets()).containsExactly(resized, promotion, alerts);
        assertThat(restored.options()).isEqualTo(options);
    }

    @Test
    void deletesOnlyTheRemovedUsersPreferenceThroughTheForeignKeyCascade() {
        var store = storeFixture(jdbc, "001");
        var userA = userFixture(jdbc, store, "A");
        var userB = userFixture(jdbc, store, "B");
        var auth = authentication("GESTION_VENTAS");
        var request = new DashboardPreferenceService.SavePreferenceRequest(
                List.of(new DashboardWidgetLayout("sales.today", 4, 1)), DashboardOptions.defaults());
        service(userA, auth, "Atlantic/Canary", NOW).save(request, auth);
        service(userB, auth, "Atlantic/Canary", NOW).save(request, auth);
        flushAndClear();

        assertThat(jdbc.update("delete from usuario where id=?", userA)).isEqualTo(1);

        assertThat(preferenceCount(userA)).isZero();
        assertThat(preferenceCount(userB)).isEqualTo(1);
    }

    @Test
    void rejectsInvalidAndNullOptionsAtTheDatabaseBoundary() throws Exception {
        var valid = "{\"defaultPeriod\":\"MONTH\",\"trendDisplay\":\"LINE\","
                + "\"productDisplay\":\"BAR\",\"density\":\"COMFORTABLE\",\"showComparison\":true}";
        var invalidJson = List.of("{}", "[]", "null",
                valid.replace("\"MONTH\"", "\"ALL_TIME\""),
                valid.replace("\"LINE\"", "\"PIE\""),
                valid.replace("\"BAR\"", "\"LINE\""),
                valid.replace("\"COMFORTABLE\"", "\"DENSE\""),
                valid.replace("true", "\"true\""), valid.replace("true", "null"),
                valid.replace("\"MONTH\"", "null"), valid.replace("\"LINE\"", "null"),
                valid.replace("\"BAR\"", "null"), valid.replace("\"COMFORTABLE\"", "null"));
        // Each statement has its own transaction so a failed CHECK cannot poison the JPA test transaction.
        try (var connection = DriverManager.getConnection(schemaUrl(), USER, PASSWORD);
             var statement = connection.prepareStatement(
                     "update preferencia_dashboard set options=cast(? as jsonb) where usuario_id=?")) {
            statement.setObject(2, LEGACY_USER);
            for (var invalid : invalidJson) {
                statement.setString(1, invalid);
                assertThatThrownBy(statement::executeUpdate).as("invalid options %s", invalid)
                        .isInstanceOfSatisfying(SQLException.class,
                                error -> assertThat(error.getSQLState()).isEqualTo("23514"));
            }
            statement.setNull(1, java.sql.Types.VARCHAR);
            assertThatThrownBy(statement::executeUpdate)
                    .isInstanceOfSatisfying(SQLException.class,
                            error -> assertThat(error.getSQLState()).isEqualTo("23502"));
        }
        assertThat(preferences.findByUser(users.findById(LEGACY_USER).orElseThrow()).orElseThrow().getOptions())
                .isEqualTo(DashboardOptions.defaults());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentFirstSavesPersistExactlyOneConfigurationAndReportTheOtherAsAConflict() throws Exception {
        concurrentSaves("971", false);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentUpdatesPreserveTheWinningConfigurationAndAllowAnExplicitRetry() throws Exception {
        concurrentSaves("972", true);
    }

    private void concurrentSaves(String code, boolean existingPreference) throws Exception {
        var store = storeFixture(jdbc, code);
        var userId = userFixture(jdbc, store, "CONCURRENT");
        var auth = authentication("GESTION_VENTAS");
        var transactions = new TransactionTemplate(transactionManager);
        if (existingPreference) {
            transactions.execute(status -> service(userId, auth, "Atlantic/Canary", NOW).save(
                    new DashboardPreferenceService.SavePreferenceRequest(
                            List.of(new DashboardWidgetLayout("sales.today", 4, 1))), auth));
        }
        var barrier = new CyclicBarrier(2);
        var synchronizedReads = (DashboardPreferenceRepository) Proxy.newProxyInstance(
                DashboardPreferenceRepository.class.getClassLoader(),
                new Class<?>[]{DashboardPreferenceRepository.class}, (proxy, method, args) -> {
                    final Object result;
                    try {
                        result = method.invoke(preferences, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                    // Both transactions must read the same absent row/version before either flushes.
                    if (method.getName().equals("findByUser")) barrier.await(10, TimeUnit.SECONDS);
                    return result;
                });
        var organization = mock(CurrentOrganization.class);
        var currentStore = mock(Store.class);
        when(currentStore.getTimezone()).thenReturn("Atlantic/Canary");
        when(organization.currentStore()).thenReturn(currentStore);
        when(organization.currentUser(auth)).thenReturn(users.findById(userId).orElseThrow());
        var concurrentService = new DashboardPreferenceService(synchronizedReads, organization,
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
        var requestA = new DashboardPreferenceService.SavePreferenceRequest(
                List.of(new DashboardWidgetLayout("sales.trend", 12, 3)),
                new DashboardOptions("TODAY", "BAR", "TABLE", "COMPACT", false));
        var requestB = new DashboardPreferenceService.SavePreferenceRequest(
                List.of(new DashboardWidgetLayout("sales.top-products", 6, 2)),
                new DashboardOptions("LAST_7_DAYS", "TABLE", "BAR", "COMFORTABLE", true));
        List<SaveOutcome> outcomes;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> concurrentSave(transactions, concurrentService, requestA, auth));
            var second = pool.submit(() -> concurrentSave(transactions, concurrentService, requestB, auth));
            outcomes = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }
        var successful = outcomes.stream().filter(outcome -> outcome.error() == null).toList();
        var failed = outcomes.stream().filter(outcome -> outcome.error() != null).toList();
        assertThat(successful).hasSize(1);
        assertThat(failed).singleElement().satisfies(outcome -> assertThat(outcome.error())
                .isInstanceOf(IllegalStateException.class).hasMessage("message.dashboard.preference_conflict"));
        assertThat(preferenceCount(userId)).isEqualTo(1);
        var winner = successful.getFirst().request();
        var stored = service(userId, auth, "Atlantic/Canary", NOW.plusSeconds(2)).get(auth);
        assertThat(stored.widgets()).containsExactlyElementsOf(winner.widgets());
        assertThat(stored.options()).isEqualTo(winner.options());
        assertThat(jdbc.queryForObject("select version from preferencia_dashboard where usuario_id=?",
                Long.class, userId)).isEqualTo(existingPreference ? 1L : 0L);

        // Retrying is an explicit new call; the service never overwrites the winner automatically.
        var retry = failed.getFirst().request();
        transactions.execute(status -> service(userId, auth, "Atlantic/Canary", NOW.plusSeconds(3)).save(retry, auth));
        var retried = service(userId, auth, "Atlantic/Canary", NOW.plusSeconds(4)).get(auth);
        assertThat(retried.widgets()).containsExactlyElementsOf(retry.widgets());
        assertThat(retried.options()).isEqualTo(retry.options());
        assertThat(preferenceCount(userId)).isEqualTo(1);
    }

    private SaveOutcome concurrentSave(TransactionTemplate transactions, DashboardPreferenceService service,
            DashboardPreferenceService.SavePreferenceRequest request, Authentication authentication) {
        try {
            transactions.execute(status -> {
                jdbc.execute("set local lock_timeout = '10s'");
                return service.save(request, authentication);
            });
            return new SaveOutcome(request, null);
        } catch (RuntimeException error) {
            return new SaveOutcome(request, error);
        }
    }

    private DashboardPreferenceService service(
            UUID userId, Authentication authentication, String timezone, Instant now) {
        var organization = mock(CurrentOrganization.class);
        var store = mock(Store.class);
        when(store.getTimezone()).thenReturn(timezone);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentUser(authentication)).thenReturn(users.findById(userId).orElseThrow());
        return new DashboardPreferenceService(preferences, organization, Clock.fixed(now, ZoneOffset.UTC));
    }

    private long preferenceCount(UUID userId) {
        return jdbc.queryForObject("select count(*) from preferencia_dashboard where usuario_id=?", Long.class, userId);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static Authentication authentication(String... permissions) {
        return UsernamePasswordAuthenticationToken.authenticated("dashboard-test", "unused",
                Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList());
    }

    private static StoreFixture storeFixture(JdbcTemplate targetJdbc, String code) {
        var companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        var address = "{\"linea1\":\"x\",\"ciudad\":\"x\",\"codigoPostal\":\"1\",\"provincia\":\"x\",\"pais\":\"ES\"}";
        targetJdbc.update("insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values (?,?,?,cast(? as jsonb))",
                companyId, "B00000" + code, "Empresa de prueba " + code, address);
        targetJdbc.update("""
                insert into tienda(id,empresa_id,nombre,direccion,address_normalized_hash,
                    timezone,moneda,locale,codigo_tienda)
                values (?,?,?,cast(? as jsonb),?,?,?,?,?)
                """, storeId, companyId, "Tienda " + code, address, "hash-" + code,
                "Atlantic/Canary", "EUR", "es-ES", code);
        targetJdbc.update("insert into rol(id,tienda_id,nombre) values (?,?,?)", roleId, storeId, "SELLER");
        return new StoreFixture(storeId, roleId);
    }

    private static UUID userFixture(JdbcTemplate targetJdbc, StoreFixture store, String name) {
        var id = UUID.randomUUID();
        targetJdbc.update("insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id) values (?,?,?,?,?,?)",
                id, store.id(), "USUARIO " + name, "dashboard-test-" + id, "fixture-only", store.roleId());
        return id;
    }

    private static String schemaUrl() {
        return URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public";
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
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record StoreFixture(UUID id, UUID roleId) {}
    private record SaveOutcome(DashboardPreferenceService.SavePreferenceRequest request, RuntimeException error) {}
}
