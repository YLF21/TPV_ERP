package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class, FamilyProductPageRepository.class})
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("postgresql")
class FamilyProductPageRepositoryPostgreSqlTest {
    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "tpv_erp_family_product_page_"
            + UUID.randomUUID().toString().replace("-", "");

    static {
        execute("create schema " + SCHEMA);
    }

    @Autowired FamilyProductPageRepository repository;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL + (URL.contains("?") ? "&" : "?")
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

    static Stream<Arguments> globalOrders() {
        return Stream.of(
                Arguments.of("code", "asc", List.of(20, 10, 30, 40, 50)),
                Arguments.of("code", "desc", List.of(30, 10, 20, 50, 40)),
                Arguments.of("name", "asc", List.of(10, 20, 30, 40, 50)),
                Arguments.of("name", "desc", List.of(50, 40, 30, 20, 10)),
                Arguments.of("salePrice", "asc", List.of(10, 20, 30, 40, 50)),
                Arguments.of("salePrice", "desc", List.of(30, 20, 10, 50, 40)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("globalOrders")
    void appliesEveryGlobalOrderAcrossKeysetPages(
            String sortBy, String direction, List<Integer> expectedOrder) {
        Fixture fixture = insertFixture();
        List<FamilyProductPageRepository.FamilyProductPageRow> rows = new ArrayList<>();
        FamilyProductPageRepository.FamilyProductPageCursor cursor = null;
        while (true) {
            var page = repository.findPage(
                    fixture.storeId(), FamilyProductPageRepository.ScopeKind.FAMILY,
                    fixture.familyId(), sortBy, direction, cursor, 2);
            rows.addAll(page);
            if (page.size() < 2) {
                break;
            }
            var last = page.getLast();
            cursor = new FamilyProductPageRepository.FamilyProductPageCursor(
                    last.nullSortValue(), last.sortValue(), last.id());
        }

        assertThat(rows).extracting(FamilyProductPageRepository.FamilyProductPageRow::id)
                .containsExactlyElementsOf(expectedOrder.stream().map(fixture::productId).toList())
                .doesNotHaveDuplicates();
        if (!"name".equals(sortBy)) {
            assertThat(rows.subList(3, 5))
                    .allMatch(FamilyProductPageRepository.FamilyProductPageRow::nullSortValue,
                            "missing code/price remain last, including descending order");
        }
        if ("code".equals(sortBy)) {
            assertThat(rows.get(1).id()).isEqualTo(fixture.productId(10));
            assertThat(rows.get(1).sortValue()).isEqualTo("B200");
            assertThat(rows.get(1).barcode()).isEqualTo("A000");
            assertThat(rows.stream().filter(row -> row.id().equals(fixture.productId(20)))
                    .findFirst().orElseThrow().sortValue()).isEqualTo("A100");
        }
    }

    @Test
    void isolatesTheExactSubfamilyInsideTheAuthenticatedStore() {
        Fixture fixture = insertFixture();

        var rows = repository.findPage(
                fixture.storeId(), FamilyProductPageRepository.ScopeKind.SUBFAMILY,
                fixture.subfamilyId(), "name", "asc", null, 20);

        assertThat(rows).extracting(FamilyProductPageRepository.FamilyProductPageRow::id)
                .containsExactly(fixture.productId(10), fixture.productId(20));
    }

    private Fixture insertFixture() {
        UUID companyId = UUID.randomUUID();
        UUID otherCompanyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID otherStoreId = UUID.randomUUID();
        UUID taxId = UUID.randomUUID();
        UUID otherTaxId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        UUID otherFamilyId = UUID.randomUUID();
        UUID otherStoreFamilyId = UUID.randomUUID();
        UUID subfamilyId = UUID.randomUUID();
        long productPrefix = UUID.randomUUID().getMostSignificantBits();
        UUID product10 = new UUID(productPrefix, 10L);
        UUID product20 = new UUID(productPrefix, 20L);
        UUID product30 = new UUID(productPrefix, 30L);
        UUID product40 = new UUID(productPrefix, 40L);
        UUID product50 = new UUID(productPrefix, 50L);
        insertCompany(companyId, taxId("B", companyId));
        insertCompany(otherCompanyId, taxId("C", otherCompanyId));
        insertStore(storeId, companyId, "101");
        insertStore(otherStoreId, otherCompanyId, "101");
        insertTax(taxId, storeId);
        insertTax(otherTaxId, otherStoreId);
        insertFamily(familyId, storeId, "001", "FAMILIA PRINCIPAL");
        insertFamily(otherFamilyId, storeId, "002", "OTRA FAMILIA");
        insertFamily(otherStoreFamilyId, otherStoreId, "001", "FAMILIA OTRA TIENDA");
        insertSubfamily(subfamilyId, familyId, "001", "001001", "SUBFAMILIA");
        insertProduct(product10, storeId, familyId, subfamilyId, taxId, "ALFA");
        insertProduct(product20, storeId, familyId, subfamilyId, taxId, "ALFA");
        insertProduct(product30, storeId, familyId, null, taxId, "BETA");
        insertProduct(product40, storeId, familyId, null, taxId, "GAMMA");
        insertProduct(product50, storeId, familyId, null, taxId, "GAMMA");
        insertProduct(UUID.randomUUID(), storeId, otherFamilyId, null, taxId, "AAA EXCLUIDO");
        insertProduct(UUID.randomUUID(), otherStoreId, otherStoreFamilyId, null,
                otherTaxId, "AAA OTRA TIENDA");
        insertIdentifier(storeId, product10, "CODIGO", "B200");
        insertIdentifier(storeId, product10, "CODIGO_BARRAS", "A000");
        insertIdentifier(storeId, product20, "CODIGO_BARRAS", "A100");
        insertIdentifier(storeId, product30, "CODIGO", "C300");
        insertPrice(product10, "10.00");
        insertPrice(product20, "20.00");
        insertPrice(product30, "20.00");
        return new Fixture(storeId, familyId, subfamilyId,
                List.of(product10, product20, product30, product40, product50));
    }

    private void insertCompany(UUID id, String taxIdentifier) {
        jdbc.update("insert into " + SCHEMA
                        + ".empresa (id,tax_id,razon_social,domicilio_fiscal)"
                        + " values (?,?,?,cast(? as jsonb))",
                id, taxIdentifier, "Empresa " + id, address());
    }

    private void insertStore(UUID id, UUID companyId, String code) {
        jdbc.update("insert into " + SCHEMA
                        + ".tienda (id,empresa_id,codigo_tienda,nombre,direccion,"
                        + "address_normalized_hash,timezone,moneda,locale)"
                        + " values (?,?,?, ?,cast(? as jsonb),?,'Atlantic/Canary','EUR','es-ES')",
                id, companyId, code, "Tienda " + id, address(), "hash-" + id);
    }

    private void insertTax(UUID id, UUID storeId) {
        jdbc.update("insert into " + SCHEMA
                        + ".impuesto_tienda (id,tienda_id,porcentaje) values (?,?,21)",
                id, storeId);
    }

    private void insertFamily(UUID id, UUID storeId, String code, String name) {
        jdbc.update("insert into " + SCHEMA
                        + ".familia (id,tienda_id,family_id,family_code,nombre,predeterminada)"
                        + " values (?,?,?,?,?,false)",
                id, storeId, code, code, name);
    }

    private void insertSubfamily(
            UUID id, UUID familyId, String suffix, String code, String name) {
        jdbc.update("insert into " + SCHEMA
                        + ".subfamilia (id,familia_id,subfamily_id,subfamily_suffix,"
                        + "subfamily_code,nombre) values (?,?,?,?,?,?)",
                id, familyId, code, suffix, code, name);
    }

    private void insertProduct(
            UUID id, UUID storeId, UUID familyId, UUID subfamilyId, UUID taxId, String name) {
        jdbc.update("insert into " + SCHEMA
                        + ".producto (id,tienda_id,familia_id,subfamilia_id,impuesto_id,nombre)"
                        + " values (?,?,?,?,?,?)",
                id, storeId, familyId, subfamilyId, taxId, name);
    }

    private void insertIdentifier(UUID storeId, UUID productId, String type, String value) {
        jdbc.update("insert into " + SCHEMA
                        + ".producto_identificador (id,tienda_id,producto_id,tipo,valor)"
                        + " values (?,?,?,?,?)",
                UUID.randomUUID(), storeId, productId, type, value);
    }

    private void insertPrice(UUID productId, String amount) {
        jdbc.update("insert into " + SCHEMA
                        + ".producto_precio (id,producto_id,tarifa,importe) values (?,?,'VENTA',?)",
                UUID.randomUUID(), productId, new BigDecimal(amount));
    }

    private static String taxId(String prefix, UUID id) {
        long value = Integer.toUnsignedLong(id.hashCode()) % 100_000_000L;
        return prefix + String.format("%08d", value);
    }

    private static String address() {
        return "{\"linea1\":\"Test\",\"ciudad\":\"Las Palmas\","
                + "\"codigoPostal\":\"35001\",\"provincia\":\"Las Palmas\",\"pais\":\"ES\"}";
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

    private record Fixture(
            UUID storeId,
            UUID familyId,
            UUID subfamilyId,
            List<UUID> productIds) {
        UUID productId(int suffix) {
            return productIds.get(suffix / 10 - 1);
        }
    }
}
