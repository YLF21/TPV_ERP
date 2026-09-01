package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class, CatalogService.class,
        ProductClassificationMovePostgreSqlTest.Configuration.class})
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class ProductClassificationMovePostgreSqlTest {

    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "product_classification_move_"
            + UUID.randomUUID().toString().replace("-", "");

    static {
        execute("create schema " + SCHEMA);
    }

    @Autowired private CatalogService catalog;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentOrganization organization;
    @MockitoBean private AuditService auditService;
    @MockitoBean private FamilyProductPageRepository familyProductPageRepository;

    private UUID companyId;
    private UUID storeId;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ProductClassificationMovePostgreSqlTest::schemaUrl);
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
    void locksRowsMovesAtomicallyIncrementsVersionsRollsBackConflictsAndAudits()
            throws Exception {
        UUID generalId = UUID.randomUUID();
        UUID sourceFamilyId = UUID.randomUUID();
        UUID targetFamilyId = UUID.randomUUID();
        UUID targetSubfamilyId = UUID.randomUUID();
        UUID taxId = UUID.randomUUID();
        UUID lowerProductId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID higherProductId = UUID.fromString("00000000-0000-0000-0000-000000000020");
        insertFamily(generalId, "000", true, "GENERAL");
        insertFamily(sourceFamilyId, "001", false, "ORIGEN");
        insertFamily(targetFamilyId, "002", false, "DESTINO");
        insertSubfamily(targetSubfamilyId, targetFamilyId, "001", "002001", "DESTINO UNO");
        insertTax(taxId);
        insertProduct(lowerProductId, sourceFamilyId, null, taxId, "PRODUCTO A");
        insertProduct(higherProductId, sourceFamilyId, null, taxId, "PRODUCTO B");

        var request = new CatalogService.BulkMoveRequest(List.of(
                new CatalogService.MoveProductItem(higherProductId, 0L),
                new CatalogService.MoveProductItem(lowerProductId, 0L)),
                null, targetSubfamilyId);

        try (Connection blocker = DriverManager.getConnection(schemaUrl(), USER, PASSWORD);
                var lock = blocker.prepareStatement(
                        "select id from producto where id = ? for update");
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            lock.setObject(1, lowerProductId);
            lock.executeQuery().close();
            var move = executor.submit(() -> catalog.moveProducts(request));

            assertThatThrownBy(() -> move.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            blocker.commit();
            CatalogService.BulkMoveResult result = move.get(10, TimeUnit.SECONDS);
            assertThat(result).isEqualTo(new CatalogService.BulkMoveResult(
                    2, 2, targetFamilyId, targetSubfamilyId));
        }

        assertClassification(lowerProductId, targetFamilyId, targetSubfamilyId, 1L);
        assertClassification(higherProductId, targetFamilyId, targetSubfamilyId, 1L);

        var staleRequest = new CatalogService.BulkMoveRequest(List.of(
                new CatalogService.MoveProductItem(lowerProductId, 1L),
                new CatalogService.MoveProductItem(higherProductId, 0L)),
                generalId, null);
        assertThatThrownBy(() -> catalog.moveProducts(staleRequest))
                .isInstanceOf(ProductClassificationVersionConflictException.class)
                .satisfies(failure -> assertThat(
                        ((ProductClassificationVersionConflictException) failure).conflicts())
                        .singleElement().satisfies(conflict -> {
                            assertThat(conflict.productId()).isEqualTo(higherProductId);
                            assertThat(conflict.expectedVersion()).isZero();
                            assertThat(conflict.currentVersion()).isEqualTo(1L);
                        }));
        assertClassification(lowerProductId, targetFamilyId, targetSubfamilyId, 1L);
        assertClassification(higherProductId, targetFamilyId, targetSubfamilyId, 1L);

        UUID missingId = UUID.randomUUID();
        var missingRequest = new CatalogService.BulkMoveRequest(List.of(
                new CatalogService.MoveProductItem(lowerProductId, 1L),
                new CatalogService.MoveProductItem(missingId, 0L)),
                generalId, null);
        assertThatThrownBy(() -> catalog.moveProducts(missingRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inexistentes");
        assertClassification(lowerProductId, targetFamilyId, targetSubfamilyId, 1L);

        var generalRequest = new CatalogService.BulkMoveRequest(List.of(
                new CatalogService.MoveProductItem(higherProductId, 1L),
                new CatalogService.MoveProductItem(lowerProductId, 1L)),
                null, null);
        catalog.moveProducts(generalRequest);
        assertClassification(lowerProductId, generalId, null, 2L);
        assertClassification(higherProductId, generalId, null, 2L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditService, times(2)).record(
                eq("PRODUCT_CLASSIFICATION_BULK_MOVED"), eq(AuditResult.EXITO), details.capture());
        List<Map<String, Object>> audits = details.getAllValues();
        assertThat(audits.getFirst().get("productIds")).isEqualTo(
                List.of(lowerProductId.toString(), higherProductId.toString()));
        assertThat(audits.getLast()).containsEntry("subfamilyId", null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> finalChanges =
                (List<Map<String, Object>>) audits.getLast().get("changes");
        assertThat(finalChanges).extracting(change -> change.get("productId"))
                .containsExactly(lowerProductId.toString(), higherProductId.toString());
        for (Map<String, Object> change : finalChanges) {
            @SuppressWarnings("unchecked")
            Map<String, Object> after = (Map<String, Object>) change.get("after");
            assertThat(after).containsEntry("familyId", generalId.toString())
                    .containsKey("subfamilyId")
                    .containsEntry("version", 2L);
            assertThat(after.get("subfamilyId")).isNull();
        }
    }

    private void assertClassification(
            UUID productId, UUID familyId, UUID subfamilyId, long version) {
        Map<String, Object> row = jdbc.queryForMap("""
                select familia_id, subfamilia_id, version from producto where id = ?
                """, productId);
        assertThat(row.get("familia_id")).isEqualTo(familyId);
        assertThat(row.get("subfamilia_id")).isEqualTo(subfamilyId);
        assertThat(row.get("version")).isEqualTo(version);
    }

    private void insertOrganization(UUID company, UUID store) {
        jdbc.update("""
                insert into empresa (id, tax_id, razon_social, domicilio_fiscal)
                values (?, ?, 'Empresa movimiento', cast(? as jsonb))
                """, company, "B" + company.toString().replace("-", "").substring(0, 8), address());
        jdbc.update("""
                insert into tienda (id, empresa_id, codigo_tienda, nombre, direccion,
                  address_normalized_hash, timezone, moneda, locale)
                values (?, ?, '402', 'Tienda movimiento', cast(? as jsonb), ?,
                  'Atlantic/Canary', 'EUR', 'es-ES')
                """, store, company, address(), "move-" + store);
    }

    private void insertFamily(UUID id, String code, boolean general, String name) {
        jdbc.update("""
                insert into familia
                  (id, tienda_id, family_id, family_code, nombre, predeterminada)
                values (?, ?, ?, ?, ?, ?)
                """, id, storeId, general ? "GENERAL" : code, code, name, general);
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

    private void insertProduct(
            UUID id, UUID familyId, UUID subfamilyId, UUID taxId, String name) {
        jdbc.update("""
                insert into producto
                  (id, tienda_id, familia_id, subfamilia_id, impuesto_id, nombre)
                values (?, ?, ?, ?, ?, ?)
                """, id, storeId, familyId, subfamilyId, taxId, name);
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

    @TestConfiguration
    static class Configuration {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-31T10:15:30Z"), ZoneOffset.UTC);
        }
    }
}
