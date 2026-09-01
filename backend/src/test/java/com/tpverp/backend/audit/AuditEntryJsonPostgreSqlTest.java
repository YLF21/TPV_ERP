package com.tpverp.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FlywayPostgreSqlConfiguration.class)
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class AuditEntryJsonPostgreSqlTest {

    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "audit_catalog_json_"
            + UUID.randomUUID().toString().replace("-", "");

    static {
        execute("create schema " + SCHEMA);
    }

    @Autowired private AuditEntryRepository audits;
    @Autowired private StoreRepository stores;
    @Autowired private JdbcTemplate jdbc;
    @PersistenceContext private EntityManager entityManager;

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

    @AfterAll
    static void dropSchema() {
        execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @Test
    void roundTripsNestedExplicitNullsAndDeterministicChangeOrderThroughJsonb() {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        insertOrganization(companyId, storeId);
        var store = stores.findById(storeId).orElseThrow();
        UUID lowerProductId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID higherProductId = UUID.fromString("00000000-0000-0000-0000-000000000020");

        List<Map<String, Object>> changes = new ArrayList<>();
        changes.add(change(lowerProductId, "001", "001001", "000", null, 4L, 5L));
        changes.add(change(higherProductId, "002", null, "000", null, 7L, 8L));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("productCount", changes.size());
        details.put("subfamilyId", null);
        details.put("productIds", List.of(lowerProductId.toString(), higherProductId.toString()));
        details.put("changes", changes);

        AuditEntry saved = audits.saveAndFlush(new AuditEntry(
                store, null, null, "PRODUCT_CLASSIFICATION_BULK_MOVED",
                AuditResult.EXITO, details, Instant.parse("2026-08-31T10:15:30Z")));
        UUID auditId = saved.getId();
        entityManager.clear();

        AuditEntry reloaded = audits.findById(auditId).orElseThrow();
        Map<String, Object> reloadedDetails = reloaded.getDatos();
        assertThat(reloadedDetails).containsKey("subfamilyId");
        assertThat(reloadedDetails.get("subfamilyId")).isNull();
        assertThat(reloadedDetails.get("productIds")).isEqualTo(
                List.of(lowerProductId.toString(), higherProductId.toString()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reloadedChanges =
                (List<Map<String, Object>>) reloadedDetails.get("changes");
        assertThat(reloadedChanges)
                .extracting(change -> change.get("productId"))
                .containsExactly(lowerProductId.toString(), higherProductId.toString());
        for (Map<String, Object> change : reloadedChanges) {
            @SuppressWarnings("unchecked")
            Map<String, Object> after = (Map<String, Object>) change.get("after");
            assertThat(after).containsKey("subfamilyId");
            assertThat(after.get("subfamilyId")).isNull();
        }

        assertThat(jdbc.queryForObject("""
                select jsonb_exists(datos, 'subfamilyId')
                       and datos->'subfamilyId' = 'null'::jsonb
                       and jsonb_exists(datos->'changes'->0->'after', 'subfamilyId')
                       and datos->'changes'->0->'after'->'subfamilyId' = 'null'::jsonb
                from auditoria where id = ?
                """, Boolean.class, auditId)).isTrue();
        assertThat(jdbc.queryForList("""
                select change->>'productId'
                from auditoria,
                     jsonb_array_elements(datos->'changes') with ordinality
                       as element(change, position)
                where id = ? order by position
                """, String.class, auditId)).containsExactly(
                        lowerProductId.toString(), higherProductId.toString());
    }

    private static Map<String, Object> change(
            UUID productId,
            String beforeFamily,
            String beforeSubfamily,
            String afterFamily,
            String afterSubfamily,
            long beforeVersion,
            long afterVersion) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("familyId", beforeFamily);
        before.put("subfamilyId", beforeSubfamily);
        before.put("version", beforeVersion);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("familyId", afterFamily);
        after.put("subfamilyId", afterSubfamily);
        after.put("version", afterVersion);
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("productId", productId.toString());
        change.put("before", before);
        change.put("after", after);
        return change;
    }

    private void insertOrganization(UUID companyId, UUID storeId) {
        jdbc.update("""
                insert into empresa (id, tax_id, razon_social, domicilio_fiscal)
                values (?, 'B23720001', 'Empresa auditoria', cast(? as jsonb))
                """, companyId, address());
        jdbc.update("""
                insert into tienda (id, empresa_id, codigo_tienda, nombre, direccion,
                  address_normalized_hash, timezone, moneda, locale)
                values (?, ?, '391', 'Tienda auditoria', cast(? as jsonb), ?,
                  'Atlantic/Canary', 'EUR', 'es-ES')
                """, storeId, companyId, address(), "audit-" + storeId);
        entityManager.flush();
        entityManager.clear();
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
}
