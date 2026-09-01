package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
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
class FamilyHierarchySearchRepositoryPostgreSqlTest {

    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "family_hierarchy_search_"
            + UUID.randomUUID().toString().replace("-", "");

    static {
        execute("create schema " + SCHEMA);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private FamilyRepository families;

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
    void projectsBothLevelsNormalizesUnicodeAndIsolatesStores() {
        Fixture fixture = insertFixture();

        List<FamilyHierarchySearchProjection> twoCharacterPrefix = search(
                fixture.storeId(), CatalogText.searchTerm("fa"), 20);
        assertThat(twoCharacterPrefix)
                .extracting(FamilyHierarchySearchProjection::getId)
                .containsExactly(fixture.facadeFamilyId(), fixture.facadeSubfamilyId())
                .doesNotContain(fixture.infixOnlyFamilyId(), fixture.otherStoreFamilyId());

        List<FamilyHierarchySearchProjection> threeCharacterInfix = search(
                fixture.storeId(), CatalogText.searchTerm("far"), 20);
        assertThat(threeCharacterInfix)
                .extracting(FamilyHierarchySearchProjection::getId)
                .containsExactly(fixture.infixOnlyFamilyId());

        List<FamilyHierarchySearchProjection> facade = search(
                fixture.storeId(), CatalogText.searchTerm("faç"), 20);
        assertThat(facade)
                .extracting(FamilyHierarchySearchProjection::getId)
                .contains(fixture.facadeFamilyId(), fixture.facadeSubfamilyId())
                .doesNotContain(fixture.otherStoreFamilyId());

        List<FamilyHierarchySearchProjection> decomposed = search(
                fixture.storeId(), CatalogText.searchTerm("éle"), 20);
        assertThat(decomposed).singleElement().satisfies(row -> {
            assertThat(row.getKind()).isEqualTo("SUBFAMILY");
            assertThat(row.getId()).isEqualTo(fixture.decomposedSubfamilyId());
            assertThat(row.getFamilyId()).isEqualTo(fixture.facadeFamilyId());
            assertThat(row.getSubfamilyId()).isEqualTo(fixture.decomposedSubfamilyId());
            assertThat(row.getCode()).isEqualTo("001002");
            assertThat(row.getFamilyCode()).isEqualTo("001");
            assertThat(row.getSuffix()).isEqualTo("002");
            assertThat(row.isDefaultFamily()).isFalse();
        });
    }

    @Test
    void appliesStableCursorAndLimitWithoutDuplicates() {
        Fixture fixture = insertFixture();
        String term = CatalogText.searchTerm("00");
        List<FamilyHierarchySearchProjection> first = families.searchHierarchy(
                fixture.storeId(), CatalogText.escapeLikeLiteral(term), 2,
                null, null, null, 2);

        assertThat(first).hasSize(2);
        FamilyHierarchySearchProjection last = first.getLast();
        List<FamilyHierarchySearchProjection> second = families.searchHierarchy(
                fixture.storeId(), CatalogText.escapeLikeLiteral(term), 2,
                last.getKind(), last.getCode(), last.getId(), 2);

        assertThat(second).isNotEmpty();
        assertThat(second).extracting(FamilyHierarchySearchProjection::getId)
                .doesNotContainAnyElementsOf(first.stream()
                        .map(FamilyHierarchySearchProjection::getId).toList());
        assertThat(concat(first, second))
                .isSortedAccordingTo((left, right) -> {
                    int kind = left.getKind().compareTo(right.getKind());
                    if (kind != 0) return kind;
                    int code = left.getCode().compareTo(right.getCode());
                    return code != 0 ? code : left.getId().compareTo(right.getId());
                });
    }

    @Test
    void treatsPercentUnderscoreAndBackslashAsLiteralSearchText() {
        Fixture fixture = insertFixture();
        UUID literalPercent = UUID.randomUUID();
        UUID percentFalsePositive = UUID.randomUUID();
        UUID literalUnderscore = UUID.randomUUID();
        UUID underscoreFalsePositive = UUID.randomUUID();
        UUID literalBackslash = UUID.randomUUID();
        UUID backslashFalsePositive = UUID.randomUUID();
        insertFamily(literalPercent, fixture.storeId(), "003", "MESA 50% REAL", false);
        insertFamily(percentFalsePositive, fixture.storeId(), "004", "MESA 500 REAL", false);
        insertSubfamily(literalUnderscore, fixture.facadeFamilyId(), "003", "001003",
                "LOTE_A REAL");
        insertSubfamily(underscoreFalsePositive, fixture.facadeFamilyId(), "004", "001004",
                "LOTEZA REAL");
        insertFamily(literalBackslash, fixture.storeId(), "005", "A\\RUTA REAL", false);
        insertFamily(backslashFalsePositive, fixture.storeId(), "006", "ABRUTA REAL", false);

        assertThat(search(fixture.storeId(), CatalogText.searchTerm("50%"), 20))
                .extracting(FamilyHierarchySearchProjection::getId)
                .containsExactly(literalPercent)
                .doesNotContain(percentFalsePositive);
        assertThat(search(fixture.storeId(), CatalogText.searchTerm("TE_"), 20))
                .extracting(FamilyHierarchySearchProjection::getId)
                .containsExactly(literalUnderscore)
                .doesNotContain(underscoreFalsePositive);
        assertThat(search(fixture.storeId(), CatalogText.searchTerm("A\\"), 20))
                .extracting(FamilyHierarchySearchProjection::getId)
                .containsExactly(literalBackslash)
                .doesNotContain(backslashFalsePositive);
    }

    @Test
    void plannerCanUseEveryV237SearchIndexOnProductionSizedHierarchy() {
        Fixture fixture = insertFixture();
        insertSearchDataset(fixture.storeId());
        jdbc.execute("analyze familia");
        jdbc.execute("analyze subfamilia");
        jdbc.execute("set local enable_seqscan = off");

        assertPlanUses("""
                explain (costs off)
                select id from familia
                where tpv_catalog_search_normalize(nombre) like 'ZZ%'
                """, "ix_familia_search_prefijo");
        assertPlanUses("""
                explain (costs off)
                select id from familia
                where tpv_catalog_search_normalize(nombre) like '%%AGUJA%%'
                """, "ix_familia_search_nombre_normalizado_trgm");
        assertPlanUses(("""
                explain (costs off)
                select id from familia
                where tienda_id = '%s' and family_code like '59%%'
                """).formatted(fixture.storeId()), "ix_familia_search_codigo_prefijo");
        assertPlanUses("""
                explain (costs off)
                select id from subfamilia
                where tpv_catalog_search_normalize(nombre) like 'YY%'
                """, "ix_subfamilia_search_prefijo");
        assertPlanUses("""
                explain (costs off)
                select id from subfamilia
                where tpv_catalog_search_normalize(nombre) like '%%ALFILER%%'
                """, "ix_subfamilia_search_nombre_normalizado_trgm");
        assertPlanUses("""
                explain (costs off)
                select id from subfamilia where subfamily_code like '12999%'
                """, "ix_subfamilia_search_codigo_prefijo");
    }

    private List<FamilyHierarchySearchProjection> search(UUID storeId, String term, int limit) {
        return families.searchHierarchy(
                storeId,
                CatalogText.escapeLikeLiteral(term),
                term.codePointCount(0, term.length()),
                null, null, null, limit);
    }

    private void assertPlanUses(String sql, String index) {
        String plan = String.join("\n", jdbc.queryForList(sql, String.class));
        assertThat(plan).contains(index);
    }

    private Fixture insertFixture() {
        UUID companyId = UUID.randomUUID();
        UUID otherCompanyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID otherStoreId = UUID.randomUUID();
        UUID generalId = UUID.randomUUID();
        UUID facadeFamilyId = UUID.randomUUID();
        UUID infixOnlyFamilyId = UUID.randomUUID();
        UUID facadeSubfamilyId = UUID.randomUUID();
        UUID decomposedSubfamilyId = UUID.randomUUID();
        UUID otherStoreFamilyId = UUID.randomUUID();
        insertCompany(companyId, "B23710001");
        insertCompany(otherCompanyId, "B23710002");
        insertStore(storeId, companyId, "381");
        insertStore(otherStoreId, otherCompanyId, "382");
        insertFamily(generalId, storeId, "000", "GENERAL", true);
        insertFamily(facadeFamilyId, storeId, "001", "FAÇANA EXTERIOR", false);
        insertFamily(infixOnlyFamilyId, storeId, "002", "ALFARERIA", false);
        insertFamily(otherStoreFamilyId, otherStoreId, "001", "FAÇANA EXTERIOR", false);
        insertSubfamily(facadeSubfamilyId, facadeFamilyId, "001", "001001", "FAÇANA INTERIOR");
        insertSubfamily(decomposedSubfamilyId, facadeFamilyId, "002", "001002",
                "E\u0301LECTRICA");
        return new Fixture(storeId, facadeFamilyId, infixOnlyFamilyId,
                facadeSubfamilyId, decomposedSubfamilyId, otherStoreFamilyId);
    }

    private void insertSearchDataset(UUID storeId) {
        jdbc.update(("""
                insert into familia
                  (id, tienda_id, family_id, family_code, nombre, predeterminada)
                select md5('family-search-' || g)::uuid, '%s', lpad(g::text, 3, '0'),
                       lpad(g::text, 3, '0'),
                       case when g = 598 then 'ZZ AGUJA CENTRAL'
                            else 'RUIDO FAMILIA ' || lpad(g::text, 3, '0') end,
                       false
                from generate_series(100, 599) g
                """).formatted(storeId));
        jdbc.update("""
                insert into subfamilia
                  (id, familia_id, subfamily_id, subfamily_suffix,
                   subfamily_code, nombre)
                select md5('sub-search-' || f || '-' || s)::uuid,
                       md5('family-search-' || f)::uuid,
                       lpad(f::text, 3, '0') || lpad(s::text, 3, '0'),
                       lpad(s::text, 3, '0'),
                       lpad(f::text, 3, '0') || lpad(s::text, 3, '0'),
                       case when f = 129 and s = 999 then 'YY ALFILER CENTRAL'
                            else 'RUIDO SUBFAMILIA ' || f || ' ' || s end
                from generate_series(100, 129) f
                cross join generate_series(1, 999) s
                """);
    }

    private void insertCompany(UUID id, String taxId) {
        jdbc.update("""
                insert into empresa (id, tax_id, razon_social, domicilio_fiscal)
                values (?, ?, 'Empresa busqueda', cast(? as jsonb))
                """, id, taxId, address());
    }

    private void insertStore(UUID id, UUID companyId, String code) {
        jdbc.update("""
                insert into tienda (id, empresa_id, codigo_tienda, nombre, direccion,
                  address_normalized_hash, timezone, moneda, locale)
                values (?, ?, ?, 'Tienda busqueda', cast(? as jsonb), ?,
                  'Atlantic/Canary', 'EUR', 'es-ES')
                """, id, companyId, code, address(), "search-" + id);
    }

    private void insertFamily(UUID id, UUID storeId, String code, String name, boolean general) {
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

    private static <T> List<T> concat(List<T> first, List<T> second) {
        return java.util.stream.Stream.concat(first.stream(), second.stream()).toList();
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

    private record Fixture(
            UUID storeId,
            UUID facadeFamilyId,
            UUID infixOnlyFamilyId,
            UUID facadeSubfamilyId,
            UUID decomposedSubfamilyId,
            UUID otherStoreFamilyId) {
    }
}
