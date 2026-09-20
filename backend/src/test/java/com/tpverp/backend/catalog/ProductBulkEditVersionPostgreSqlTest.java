package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import com.tpverp.backend.security.domain.UserAccount;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class, CatalogService.class, ProductBulkEditService.class, ProductBulkCodeSequenceRepository.class,
        ProductBulkEditVersionPostgreSqlTest.Configuration.class})
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductBulkEditVersionPostgreSqlTest {
    private static final String URL = System.getenv("TPV_ERP_TEST_DB_URL");
    private static final String USER = System.getenv("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "bulk_edit_version_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired private ProductBulkEditService service;
    @Autowired private CatalogService catalog;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private CurrentOrganization organization;
    @MockitoBean private AuditService audit;
    @MockitoBean private FamilyProductPageRepository familyProductPages;
    @MockitoBean private ProductSupplierService suppliers;
    @MockitoBean private ProductImageService productImages;
    private UUID storeId;
    private UUID familyId;
    private UUID secondFamilyId;
    private UUID taxId;
    private UUID secondTaxId;
    private final UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken("manager", "test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @BeforeEach
    void prepare() {
        UUID companyId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        familyId = UUID.randomUUID();
        secondFamilyId = UUID.randomUUID();
        taxId = UUID.randomUUID();
        secondTaxId = UUID.randomUUID();
        String address = "{\"linea1\":\"Test\",\"ciudad\":\"Las Palmas\",\"codigoPostal\":\"35001\",\"provincia\":\"Las Palmas\",\"pais\":\"ES\"}";
        jdbc.update("insert into empresa (id,tax_id,razon_social,domicilio_fiscal) values (?,?,'Bulk test',cast(? as jsonb))",
                companyId, "B" + companyId.toString().replace("-", "").substring(0, 8), address);
        jdbc.update("insert into tienda (id,empresa_id,codigo_tienda,nombre,direccion,address_normalized_hash,timezone,moneda,locale) values (?,?,'401','Bulk test',cast(? as jsonb),?,'Atlantic/Canary','EUR','es-ES')",
                storeId, companyId, address, "bulk-" + storeId);
        jdbc.update("insert into rol (id,tienda_id,nombre,protegido) values (?,?,'GESTOR',false)", roleId, storeId);
        jdbc.update("insert into usuario (id,tienda_id,nombre,password_hash,rol_id,user_name) values (?,?,'GESTOR','test',?,'Gestor')",
                userId, storeId, roleId);
        jdbc.update("insert into familia (id,tienda_id,family_id,family_code,nombre,predeterminada) values (?,?,'GENERAL','000','GENERAL',true)", familyId, storeId);
        jdbc.update("insert into familia (id,tienda_id,family_id,family_code,nombre,predeterminada) values (?,?,'001','001','SEGUNDA',false)", secondFamilyId, storeId);
        jdbc.update("insert into impuesto_tienda (id,tienda_id,porcentaje) values (?,?,0),(?,?,7)", taxId, storeId, secondTaxId, storeId);
        Store store = mock(Store.class);
        UserAccount user = mock(UserAccount.class);
        when(store.getId()).thenReturn(storeId);
        when(user.getId()).thenReturn(userId);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentUser(authentication)).thenReturn(user);
    }

    @Test
    void returnedVersionsAllowFamilyAndTaxChangesThenAnotherApplication() {
        var base = new TransactionTemplate(transactionManager).execute(status ->
                ProductBulkEditContent.ProductData.fromProduct(catalog.createProduct(request(familyId, taxId))));
        var created = service.create(new ProductBulkEditService.ProductBulkCreateRequest("Familia e impuesto", List.of(row(base, ProductBulkEditContent.ProductData.empty()))), authentication);
        var first = applyClassification(created, base, secondFamilyId, secondTaxId);
        assertPersistenceVersions(first);
        var reopened = service.update(first.id(), new ProductBulkEditService.ProductBulkUpdateRequest(first.version(), first.name(), first.content()), authentication);
        var second = applyClassification(reopened, first.content().getFirst().product(), familyId, secondTaxId);
        assertPersistenceVersions(second);
        assertThat(jdbc.queryForObject("select familia_id from producto where id = ?", UUID.class, base.productId())).isEqualTo(familyId);
        assertThat(jdbc.queryForObject("select impuesto_id from producto where id = ?", UUID.class, base.productId())).isEqualTo(secondTaxId);
    }

    @Test
    void openingAnOldUnchangedListRefreshesTheWholeProductWithoutWritingTheDraft() {
        var base = new TransactionTemplate(transactionManager).execute(status ->
                ProductBulkEditContent.ProductData.fromProduct(catalog.createProduct(request(familyId, taxId))));
        var saved = service.create(new ProductBulkEditService.ProductBulkCreateRequest("Guardada", List.of(row(base, base))), authentication);
        String storedContent = jdbc.queryForObject("select contenido::text from producto_edicion_masiva where id = ?", String.class, saved.id());
        catalog.updateProduct(base.productId(), request(secondFamilyId, taxId));
        var staleAttempt = classificationData(base, familyId, secondTaxId);
        assertThatThrownBy(() -> service.apply(saved.id(), new ProductBulkEditService.ProductBulkApplyRequest(
                saved.version(), List.of(new CatalogService.BulkProductUpdate(base.productId(), base.version(), request(familyId, secondTaxId))),
                List.of(), List.of(row(staleAttempt, staleAttempt))), authentication))
                .isInstanceOf(ProductBulkEditConflictException.class);
        var opened = service.get(saved.id());
        assertThat(opened.productSnapshotsRefreshed()).isTrue();
        assertThat(opened.content().getFirst().product().familyId()).isEqualTo(secondFamilyId.toString());
        assertThat(opened.content().getFirst().product().familyName()).isEqualTo("SEGUNDA");
        assertThat(opened.content().getFirst().product().taxName()).isEqualTo("0%");
        assertThat(opened.content().getFirst().product().version()).isGreaterThan(base.version());
        assertThat(opened.version()).isEqualTo(saved.version());
        assertThat(jdbc.queryForObject("select contenido::text from producto_edicion_masiva where id = ?", String.class, saved.id())).isEqualTo(storedContent);
        var applied = applyClassification(opened, opened.content().getFirst().product(), familyId, secondTaxId);
        assertPersistenceVersions(applied);
    }

    @Test
    void openingAnOldListWithSavedChangesPreservesThemAndRejectsAnActualProductConflict() {
        var base = new TransactionTemplate(transactionManager).execute(status ->
                ProductBulkEditContent.ProductData.fromProduct(catalog.createProduct(request(familyId, taxId))));
        var pending = classificationData(base, secondFamilyId, secondTaxId);
        var saved = service.create(new ProductBulkEditService.ProductBulkCreateRequest("Cambios pendientes", List.of(row(base, pending))), authentication);
        catalog.updateProduct(base.productId(), request(secondFamilyId, taxId));
        var opened = service.get(saved.id());
        assertThat(opened.productSnapshotsRefreshed()).isFalse();
        assertThat(opened.content()).isEqualTo(saved.content());
        assertThatThrownBy(() -> service.apply(opened.id(), new ProductBulkEditService.ProductBulkApplyRequest(
                opened.version(), List.of(new CatalogService.BulkProductUpdate(base.productId(), base.version(), request(secondFamilyId, secondTaxId))),
                List.of(), List.of(row(pending, pending))), authentication))
                .isInstanceOfSatisfying(ProductBulkEditConflictException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("BULK_EDIT_PRODUCT_VERSION_CONFLICT");
                    assertThat(failure.conflicts().getFirst().productId()).isEqualTo(base.productId());
                    assertThat(failure.conflicts().getFirst().actualVersion()).isGreaterThan(base.version());
                });
        assertThat(jdbc.queryForObject("select impuesto_id from producto where id = ?", UUID.class, base.productId())).isEqualTo(taxId);
        assertThat(jdbc.queryForObject("select estado from producto_edicion_masiva where id = ?", String.class, saved.id())).isEqualTo("PENDING");
    }

    private ProductBulkEditView applyClassification(ProductBulkEditView current, ProductBulkEditContent.ProductData base, UUID family, UUID tax) {
        var changed = classificationData(base, family, tax);
        var saved = service.update(current.id(), new ProductBulkEditService.ProductBulkUpdateRequest(current.version(), current.name(), List.of(row(base, changed))), authentication);
        return service.apply(saved.id(), new ProductBulkEditService.ProductBulkApplyRequest(saved.version(),
                List.of(new CatalogService.BulkProductUpdate(base.productId(), base.version(), request(family, tax))),
                List.of(), List.of(row(changed, changed))), authentication);
    }

    private static ProductBulkEditContent.ProductData classificationData(
            ProductBulkEditContent.ProductData p, UUID family, UUID tax) {
        return new ProductBulkEditContent.ProductData(p.productId(), p.version(), p.imageId(), p.warehouseId(),
                p.code(), p.barcode(), p.barcode2(), p.name(), p.description(), p.comments(), p.purchasePrice(),
                p.purchaseDiscountPercent(), p.salePrice(), p.memberPrice(), p.wholesalePrice(), p.offerPrice(),
                p.offerDiscountPercent(), p.productType(), p.discountType(), p.backendDiscountType(),
                family.toString(), p.familyName(), null, null, tax.toString(), p.taxName(), p.taxesIncluded(),
                p.offerActive(), p.offerFrom(), p.offerUntil(), p.warehouseName(), p.quantity(), p.totalQuantity(),
                p.stockMin(), p.stockMax(), p.active(), p.packageQuantity());
    }

    private void assertPersistenceVersions(ProductBulkEditView result) {
        assertThat(result.version()).isEqualTo(jdbc.queryForObject("select version from producto_edicion_masiva where id = ?", Long.class, result.id()));
        var product = result.content().getFirst().product();
        assertThat(product.version()).isEqualTo(jdbc.queryForObject("select version from producto where id = ?", Long.class, product.productId()));
    }

    private static ProductBulkEditContent.Row row(ProductBulkEditContent.ProductData product, ProductBulkEditContent.ProductData draft) {
        return new ProductBulkEditContent.Row("row-1", true, "P-1", product, draft, List.of(), null);
    }

    private static CatalogService.ProductRequest request(UUID family, UUID tax) {
        return new CatalogService.ProductRequest(family, null, tax, ProductType.UNIT, DiscountType.NORMAL,
                PriceUseMode.NORMAL, "PRODUCTO", null, null, BigDecimal.TEN, true, "P-1", null, null,
                new BigDecimal("12.00"), null, null, null, null, BigDecimal.ZERO, false, null, null,
                null, null, BigDecimal.ONE, true);
    }

    @AfterAll
    static void cleanup() throws Exception {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD); var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + SCHEMA + " cascade");
        }
    }

    @TestConfiguration
    static class Configuration {
        @Bean @Primary Clock clock() { return Clock.systemUTC(); }
    }
}
