package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class, CatalogService.class,
        CatalogDeleteConcurrencyPostgreSqlTest.Configuration.class})
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class CatalogDeleteConcurrencyPostgreSqlTest {

    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "catalog_delete_concurrency_"
            + UUID.randomUUID().toString().replace("-", "");

    static {
        execute("create schema " + SCHEMA);
    }

    @Autowired private CatalogService catalog;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StoreRepository stores;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private CurrentOrganization organization;
    @MockitoBean private AuditService auditService;
    @MockitoBean private FamilyProductPageRepository familyProductPageRepository;

    private UUID companyId;
    private UUID storeId;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL
                + (URL.contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @BeforeEach
    void organizationContext() {
        companyId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        insertOrganization(companyId, storeId);
        Store store = mock(Store.class);
        Company company = mock(Company.class);
        when(store.getId()).thenReturn(storeId);
        when(company.getId()).thenReturn(companyId);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
    }

    @AfterAll
    static void dropSchema() {
        execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void overlappingFamilyAndChildDeletesNeverDeadlockAndLeaveAValidHierarchy()
            throws Exception {
        insertFamily(UUID.randomUUID(), "000", true, "GENERAL");
        for (int round = 1; round <= 8; round++) {
            UUID familyId = UUID.randomUUID();
            UUID subfamilyId = UUID.randomUUID();
            insertFamily(familyId, "%03d".formatted(round), false, "FAMILIA " + round);
            insertSubfamily(subfamilyId, familyId, "%03d".formatted(round),
                    "%03d%03d".formatted(round, round), "SUBFAMILIA " + round);

            CountDownLatch start = new CountDownLatch(1);
            Throwable familyFailure;
            Throwable childFailure;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var familyDelete = executor.submit(() -> executeDelete(start,
                        () -> catalog.deleteFamily(familyId, true)));
                var childDelete = executor.submit(() -> executeDelete(start,
                        () -> catalog.deleteSubfamily(subfamilyId, true)));
                start.countDown();
                familyFailure = familyDelete.get(15, TimeUnit.SECONDS);
                childFailure = childDelete.get(15, TimeUnit.SECONDS);
            }

            assertThat(sqlState(familyFailure)).isNotEqualTo("40P01");
            assertThat(sqlState(childFailure)).isNotEqualTo("40P01");
            assertThat(familyFailure == null || childFailure == null).isTrue();
            assertThat(jdbc.queryForObject(
                    "select count(*) from familia where id = ?", Integer.class, familyId))
                    .isZero();
            assertThat(jdbc.queryForObject(
                    "select count(*) from subfamilia where id = ?", Integer.class, subfamilyId))
                    .isZero();
            assertThat(jdbc.queryForObject("""
                    select count(*) from familia_codigo_reservado
                    where tienda_id = ? and family_code = ?
                    """, Integer.class, storeId, "%03d".formatted(round))).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    select count(*) from subfamilia_codigo_reservado
                    where familia_id = ? and subfamily_suffix = ?
                    """, Integer.class, familyId, "%03d".formatted(round))).isEqualTo(1);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deleteAndMoveQueueOnStoreMutexWithoutDeadlockAndLeaveProductValid()
            throws Exception {
        UUID generalId = UUID.randomUUID();
        UUID targetFamilyId = UUID.randomUUID();
        UUID taxId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        insertFamily(generalId, "000", true, "GENERAL");
        insertFamily(targetFamilyId, "010", false, "DESTINO");
        insertTax(taxId);
        insertProduct(productId, generalId, taxId, "PRODUCTO CONCURRENTE");
        var request = new CatalogService.BulkMoveRequest(
                java.util.List.of(new CatalogService.MoveProductItem(productId, 0L)),
                targetFamilyId, null);

        Throwable deleteFailure;
        Throwable moveFailure;
        try (Connection blocker = DriverManager.getConnection(schemaUrl(), USER, PASSWORD);
                var lock = blocker.prepareStatement("select id from tienda where id = ? for update");
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            lock.setObject(1, storeId);
            lock.executeQuery().close();
            CountDownLatch start = new CountDownLatch(1);
            var delete = executor.submit(() -> executeConcurrent(start,
                    () -> catalog.deleteFamily(targetFamilyId, true)));
            var move = executor.submit(() -> executeConcurrent(start,
                    () -> catalog.moveProducts(request)));
            start.countDown();

            assertThatThrownBy(() -> delete.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThatThrownBy(() -> move.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            blocker.commit();
            deleteFailure = delete.get(10, TimeUnit.SECONDS);
            moveFailure = move.get(10, TimeUnit.SECONDS);
        }

        assertThat(deleteFailure).isNull();
        assertThat(sqlState(moveFailure)).isNotEqualTo("40P01");
        assertThat(jdbc.queryForObject(
                "select count(*) from familia where id = ?", Integer.class, targetFamilyId))
                .isZero();
        assertThat(jdbc.queryForMap(
                "select familia_id, subfamilia_id from producto where id = ?", productId))
                .containsEntry("familia_id", generalId)
                .containsEntry("subfamilia_id", null);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void storeMutexSerializesOneStoreWithoutBlockingAnotherStoreOfSameCompany()
            throws Exception {
        UUID otherStoreId = UUID.randomUUID();
        insertStore(companyId, otherStoreId, "402");
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        try (Connection blocker = DriverManager.getConnection(schemaUrl(), USER, PASSWORD);
                var lock = blocker.prepareStatement("select id from tienda where id = ? for update");
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            lock.setObject(1, storeId);
            lock.executeQuery().close();

            var sameStore = executor.submit(() -> transactions.executeWithoutResult(ignored ->
                    stores.findByIdForUpdate(storeId).orElseThrow()));
            var otherStore = executor.submit(() -> transactions.executeWithoutResult(ignored ->
                    stores.findByIdForUpdate(otherStoreId).orElseThrow()));

            assertThatThrownBy(() -> sameStore.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            otherStore.get(2, TimeUnit.SECONDS);
            blocker.commit();
            sameStore.get(2, TimeUnit.SECONDS);
        }
    }

    private Throwable executeDelete(CountDownLatch start, ThrowingRunnable action) {
        return executeConcurrent(start, action);
    }

    private Throwable executeConcurrent(CountDownLatch start, ThrowingRunnable action) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                return new IllegalStateException("No se inicio la ronda concurrente");
            }
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static String sqlState(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return sqlException.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }

    private void insertOrganization(UUID company, UUID store) {
        jdbc.update("""
                insert into empresa (id, tax_id, razon_social, domicilio_fiscal)
                values (?, ?, 'Empresa borrado', cast(? as jsonb))
                """, company, "B" + company.toString().replace("-", "").substring(0, 8), address());
        insertStore(company, store, "401");
    }

    private void insertStore(UUID company, UUID store, String code) {
        jdbc.update("""
                insert into tienda (id, empresa_id, codigo_tienda, nombre, direccion,
                  address_normalized_hash, timezone, moneda, locale)
                values (?, ?, ?, 'Tienda borrado', cast(? as jsonb), ?,
                  'Atlantic/Canary', 'EUR', 'es-ES')
                """, store, company, code, address(), "delete-" + store);
    }

    private void insertFamily(UUID id, String code, boolean general, String name) {
        jdbc.update("""
                insert into familia
                  (id, tienda_id, family_id, family_code, nombre, predeterminada)
                values (?, ?, ?, ?, ?, ?)
                """, id, storeId, general ? "GENERAL" : code, code,
                general ? "GENERAL" : name, general);
    }

    private void insertSubfamily(
            UUID id, UUID familyId, String suffix, String code, String name) {
        jdbc.update("""
                insert into subfamilia
                  (id, familia_id, subfamily_id, subfamily_suffix,
                   subfamily_code, nombre)
                values (?, ?, ?, ?, ?, ?)
                """, id, familyId, code, suffix, code, name);
    }

    private void insertTax(UUID id) {
        jdbc.update("insert into impuesto_tienda (id, tienda_id, porcentaje) values (?, ?, 21)",
                id, storeId);
    }

    private void insertProduct(UUID id, UUID familyId, UUID taxId, String name) {
        jdbc.update("""
                insert into producto (id, tienda_id, familia_id, impuesto_id, nombre)
                values (?, ?, ?, ?, ?)
                """, id, storeId, familyId, taxId, name);
    }

    private static String schemaUrl() {
        return URL + (URL.contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA + ",public";
    }

    private static String address() {
        return """
                {"linea1":"Test","ciudad":"Las Palmas","codigoPostal":"35001",
                 "provincia":"Las Palmas","pais":"ES"}
                """;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " no configurada");
        }
        return value;
    }

    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException("No se pudo preparar PostgreSQL", exception);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @TestConfiguration
    static class Configuration {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-31T10:15:30Z"), ZoneOffset.UTC);
        }
    }
}
