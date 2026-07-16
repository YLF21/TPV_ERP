package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import com.tpverp.backend.inventory.StockLevelRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@ExtendWith(MockitoExtension.class)
class ProductPriceRuleServiceTest {

    @Mock private CurrentOrganization organization;
    @Mock private UserAccountRepository users;
    @Mock private ProductPriceRuleRepository repository;
    @Mock private ProductRepository products;
    @Mock private ProductSupplierRepository productSuppliers;
    @Mock private StockLevelRepository stockLevels;
    @Mock private WarehouseRepository warehouses;
    @Mock private CatalogService catalog;
    @Mock private Company company;
    @Mock private Store store;
    @Mock private UserAccount user;

    private ProductPriceRuleService service;
    private UUID companyId;
    private UUID storeId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        userId = UUID.randomUUID();
        org.mockito.Mockito.lenient().when(company.getId()).thenReturn(companyId);
        org.mockito.Mockito.lenient().when(store.getId()).thenReturn(storeId);
        org.mockito.Mockito.lenient().when(organization.currentCompany()).thenReturn(company);
        org.mockito.Mockito.lenient().when(organization.currentStore()).thenReturn(store);
        org.mockito.Mockito.lenient().when(organization.currentUser(any())).thenReturn(user);
        org.mockito.Mockito.lenient().when(user.getId()).thenReturn(userId);
        service = new ProductPriceRuleService(
                organization,
                users,
                repository,
                products,
                productSuppliers,
                stockLevels,
                warehouses,
                catalog,
                Clock.fixed(Instant.parse("2026-07-11T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void previewEvaluatesPriceConditionsAndDoesNotPersist() {
        Product product = product();

        ProductPriceRuleForm.Definition form = new ProductPriceRuleForm.Definition(
                ProductPriceRuleForm.Scope.PRICES,
                List.of(
                        new ProductPriceRuleForm.NumericCondition(
                                ProductPriceRuleForm.NumericField.GROSS_COST,
                                ProductPriceRuleForm.NumericComparator.GTE,
                                new BigDecimal("10.00"), null),
                        new ProductPriceRuleForm.NumericCondition(
                                ProductPriceRuleForm.NumericField.NET_COST,
                                ProductPriceRuleForm.NumericComparator.EQ,
                                new BigDecimal("8.00"), null)),
                List.of(
                        new ProductPriceRuleForm.InverseMarginAction(
                                ProductPriceRuleForm.PriceField.SALE_PRICE,
                                ProductPriceRuleForm.CostBasis.NET,
                                new BigDecimal("20.00")),
                        new ProductPriceRuleForm.DecimalEndingAction(
                                ProductPriceRuleForm.PriceField.SALE_PRICE, 99)));
        ProductPriceRule rule = rule(userId, List.of(form));
        stubRule(rule, product);

        ProductPriceRulePreview preview = service.preview(
                rule.getId(), rule.getVersion(), List.of(product.getId()));

        assertThat(preview.matchedProducts()).isEqualTo(1);
        assertThat(preview.products()).singleElement().satisfies(change -> {
            assertThat(change.productId()).isEqualTo(product.getId());
            assertThat(change.changes()).singleElement().satisfies(field -> {
                assertThat(field.field()).isEqualTo(ProductPriceRulePreview.Field.SALE_PRICE);
                assertThat(field.before()).isEqualTo(new BigDecimal("15.00"));
                assertThat(field.after()).isEqualTo(new BigDecimal("10.99"));
                assertThat(field.formIndexes()).containsExactly(0);
            });
        });
        verify(catalog).validateProductUpdate(
                org.mockito.ArgumentMatchers.eq(product.getId()), any());
        verify(catalog, never()).updateProducts(any());
    }

    @Test
    void offerPriceActionsSelectOfferModeAndApplyDecimalAfterTheBasePrice() {
        Product product = product();
        ProductPriceRuleForm.Definition form = new ProductPriceRuleForm.Definition(
                ProductPriceRuleForm.Scope.OFFER,
                List.of(),
                List.of(
                        new ProductPriceRuleForm.DecimalEndingAction(
                                ProductPriceRuleForm.PriceField.OFFER_PRICE, 95),
                        new ProductPriceRuleForm.FixedPriceAction(
                                ProductPriceRuleForm.PriceField.OFFER_PRICE,
                                new BigDecimal("12.01")),
                        new ProductPriceRuleForm.OfferAction(
                                true, null, java.time.LocalDate.of(2026, 7, 11), null)));
        ProductPriceRule rule = rule(userId, List.of(form));
        stubRule(rule, product);

        service.preview(rule.getId(), rule.getVersion(), List.of(product.getId()));

        ArgumentCaptor<CatalogService.ProductRequest> captor =
                ArgumentCaptor.forClass(CatalogService.ProductRequest.class);
        verify(catalog).validateProductUpdate(
                org.mockito.ArgumentMatchers.eq(product.getId()), captor.capture());
        assertThat(captor.getValue().priceUseMode()).isEqualTo(PriceUseMode.OFFER_PRICE);
        assertThat(captor.getValue().discountType()).isEqualTo(DiscountType.DISCOUNT_PRICE);
        assertThat(captor.getValue().offerPrice()).isEqualByComparingTo("12.95");
        assertThat(captor.getValue().offerDiscountPercent()).isNull();
        assertThat(captor.getValue().offerActive()).isTrue();
        assertThat(captor.getValue().offerFrom())
                .isEqualTo(java.time.LocalDate.of(2026, 7, 11));
    }

    @Test
    void offerDiscountAutomaticallySelectsModeAndDerivesOfferPrice() {
        Product product = product();
        ProductPriceRuleForm.Definition form = new ProductPriceRuleForm.Definition(
                ProductPriceRuleForm.Scope.OFFER,
                List.of(),
                List.of(new ProductPriceRuleForm.OfferAction(
                        true,
                        new BigDecimal("10.00"),
                        java.time.LocalDate.of(2026, 7, 11),
                        null)));
        ProductPriceRule rule = rule(userId, List.of(form));
        stubRule(rule, product);

        service.preview(rule.getId(), rule.getVersion(), List.of(product.getId()));

        ArgumentCaptor<CatalogService.ProductRequest> captor =
                ArgumentCaptor.forClass(CatalogService.ProductRequest.class);
        verify(catalog).validateProductUpdate(
                org.mockito.ArgumentMatchers.eq(product.getId()), captor.capture());
        assertThat(captor.getValue().priceUseMode()).isEqualTo(PriceUseMode.OFFER_DISCOUNT);
        assertThat(captor.getValue().discountType()).isEqualTo(DiscountType.DISCOUNT_PRICE);
        assertThat(captor.getValue().offerDiscountPercent()).isEqualByComparingTo("10.00");
        assertThat(captor.getValue().offerPrice()).isEqualByComparingTo("13.50");
        assertThat(captor.getValue().offerActive()).isTrue();
    }

    @Test
    void matchingOfferPriceAndOfferDiscountFormsReturnModeConflict() {
        Product product = product();
        ProductPriceRuleForm.Definition offerPrice = new ProductPriceRuleForm.Definition(
                ProductPriceRuleForm.Scope.OFFER,
                List.of(),
                List.of(
                        new ProductPriceRuleForm.FixedPriceAction(
                                ProductPriceRuleForm.PriceField.OFFER_PRICE,
                                new BigDecimal("13.50")),
                        new ProductPriceRuleForm.OfferAction(
                                true, null, java.time.LocalDate.of(2026, 7, 11), null)));
        ProductPriceRuleForm.Definition offerDiscount = new ProductPriceRuleForm.Definition(
                ProductPriceRuleForm.Scope.OFFER,
                List.of(),
                List.of(new ProductPriceRuleForm.OfferAction(
                        true,
                        new BigDecimal("10.00"),
                        java.time.LocalDate.of(2026, 7, 11),
                        null)));
        ProductPriceRule rule = rule(userId, List.of(offerPrice, offerDiscount));
        stubRule(rule, product);

        assertThatThrownBy(() -> service.preview(
                rule.getId(), rule.getVersion(), List.of(product.getId())))
                .isInstanceOfSatisfying(ProductPriceRuleConflictException.class, conflict -> {
                    assertThat(conflict.getProductId()).isEqualTo(product.getId());
                    assertThat(conflict.getField())
                            .isEqualTo(ProductPriceRulePreview.Field.PRICE_USE_MODE);
                    assertThat(conflict.getFormIndexes()).containsExactly(0, 1);
                });
    }

    @Test
    void differentFormsChangingTheSameFieldReturnProductConflict() {
        Product product = product();
        ProductPriceRule rule = rule(userId, List.of(
                fixedPriceForm("20.00"), fixedPriceForm("21.00")));
        stubRule(rule, product);

        assertThatThrownBy(() -> service.preview(
                rule.getId(), rule.getVersion(), List.of(product.getId())))
                .isInstanceOfSatisfying(ProductPriceRuleConflictException.class, conflict -> {
                    assertThat(conflict.getProductId()).isEqualTo(product.getId());
                    assertThat(conflict.getField()).isEqualTo(ProductPriceRulePreview.Field.SALE_PRICE);
                    assertThat(conflict.getFormIndexes()).containsExactly(0, 1);
                });
    }

    @Test
    void rulesAreCreatedForTheCurrentCompanyAndCreator() {
        service.create(new ProductPriceRuleService.ProductPriceRuleCreateRequest(
                "Tarifa verano", List.of(fixedPriceForm("20.00"))), manager());

        ArgumentCaptor<ProductPriceRule> captor = ArgumentCaptor.forClass(ProductPriceRule.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getCompanyId()).isEqualTo(companyId);
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(userId);
    }

    @Test
    void onlyCreatorOrAdminCanDelete() {
        ProductPriceRule rule = rule(UUID.randomUUID(), List.of(fixedPriceForm("20.00")));
        when(repository.findByIdAndCompanyId(rule.getId(), companyId)).thenReturn(Optional.of(rule));

        assertThatThrownBy(() -> service.delete(rule.getId(), rule.getVersion(), manager()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("ADMIN o el creador");

        service.delete(rule.getId(), rule.getVersion(), admin());
        verify(repository).delete(rule);
    }

    @Test
    void previewRejectsStaleRuleVersionBeforeEvaluation() {
        ProductPriceRule rule = rule(userId, List.of(fixedPriceForm("20.00")));
        when(repository.findByIdAndCompanyId(rule.getId(), companyId)).thenReturn(Optional.of(rule));

        assertThatThrownBy(() -> service.preview(
                rule.getId(), rule.getVersion() + 1, List.of(UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicto de version");

        verify(products, never()).findAllByStoreIdAndIdIn(any(), any());
    }

    @Test
    void previewRevalidatesStoredRulesBeforeLoadingProducts() {
        UUID ruleId = UUID.randomUUID();
        ProductPriceRule rule = mock(ProductPriceRule.class);
        ProductPriceRuleForm.Definition invalid = new ProductPriceRuleForm.Definition(
                ProductPriceRuleForm.Scope.PRICES,
                List.of(new ProductPriceRuleForm.NumericCondition(
                        ProductPriceRuleForm.NumericField.TOTAL_STOCK,
                        ProductPriceRuleForm.NumericComparator.GT,
                        BigDecimal.ZERO,
                        null)),
                List.of(new ProductPriceRuleForm.FixedPriceAction(
                        ProductPriceRuleForm.PriceField.SALE_PRICE,
                        new BigDecimal("20.00"))));
        when(rule.getVersion()).thenReturn(0L);
        when(rule.getForms()).thenReturn(List.of(invalid));
        when(repository.findByIdAndCompanyId(ruleId, companyId)).thenReturn(Optional.of(rule));

        assertThatThrownBy(() -> service.preview(ruleId, 0L, List.of(UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NUMBER.TOTAL_STOCK no se admite para PRICES");

        verify(products, never()).findAllByStoreIdAndIdIn(any(), any());
    }

    @Test
    void previewRunsFinalCatalogValidation() {
        Product product = product();
        ProductPriceRule rule = rule(userId, List.of(fixedPriceForm("20.00")));
        stubRule(rule, product);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("oferta no valida"))
                .when(catalog).validateProductUpdate(
                        org.mockito.ArgumentMatchers.eq(product.getId()), any());

        assertThatThrownBy(() -> service.preview(
                rule.getId(), rule.getVersion(), List.of(product.getId())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oferta no valida");

        verify(catalog, never()).updateProducts(any());
    }

    @Test
    void inverseMarginRejectsUnsafePrecisionNearOneHundred() {
        assertThatThrownBy(() -> new ProductPriceRuleForm.InverseMarginAction(
                ProductPriceRuleForm.PriceField.SALE_PRICE,
                ProductPriceRuleForm.CostBasis.NET,
                new BigDecimal("99.999")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99.99");
    }

    @Test
    void previewRejectsTargetsOutsideTheSelectedStoreProducts() {
        Product selected = product();
        UUID missingProductId = UUID.randomUUID();
        ProductPriceRule rule = rule(userId, List.of(fixedPriceForm("20.00")));
        when(repository.findByIdAndCompanyId(rule.getId(), companyId)).thenReturn(Optional.of(rule));
        when(products.findAllByStoreIdAndIdIn(
                org.mockito.ArgumentMatchers.eq(storeId), any())).thenReturn(List.of(selected));

        assertThatThrownBy(() -> service.preview(
                rule.getId(), rule.getVersion(), List.of(selected.getId(), missingProductId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tienda actual");

        verify(catalog, never()).validateProductUpdate(any(), any());
    }

    private void stubRule(ProductPriceRule rule, Product product) {
        when(repository.findByIdAndCompanyId(rule.getId(), companyId)).thenReturn(Optional.of(rule));
        when(products.findAllByStoreIdAndIdIn(
                org.mockito.ArgumentMatchers.eq(storeId), any())).thenReturn(List.of(product));
    }

    private ProductPriceRule rule(
            UUID creatorId,
            List<ProductPriceRuleForm.Definition> forms) {
        return new ProductPriceRule(
                companyId,
                "Regla",
                forms,
                creatorId,
                Instant.parse("2026-07-11T09:00:00Z"));
    }

    private static ProductPriceRuleForm.Definition fixedPriceForm(String price) {
        return new ProductPriceRuleForm.Definition(
                ProductPriceRuleForm.Scope.PRICES,
                List.of(),
                List.of(new ProductPriceRuleForm.FixedPriceAction(
                        ProductPriceRuleForm.PriceField.SALE_PRICE,
                        new BigDecimal(price))));
    }

    private Product product() {
        Product product = new Product(
                storeId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                ProductType.UNIT,
                DiscountType.NORMAL,
                "Producto",
                "Descripcion",
                "Comentario",
                new BigDecimal("10.00"),
                false);
        product.replaceIdentifier(IdentifierType.CODIGO, "P-1");
        product.setPrice(PriceTier.VENTA, new BigDecimal("15.00"));
        product.configurePurchaseDiscount(new BigDecimal("20.00"));
        product.configurePriceUse(PriceUseMode.NORMAL, null);
        return product;
    }

    private UsernamePasswordAuthenticationToken manager() {
        return new UsernamePasswordAuthenticationToken(
                "manager", "", List.of(new SimpleGrantedAuthority("GESTION_PRODUCTO")));
    }

    private UsernamePasswordAuthenticationToken admin() {
        return new UsernamePasswordAuthenticationToken(
                "admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
}
