package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

import com.tpverp.backend.inventory.StockLevelRepository;
import com.tpverp.backend.inventory.StockMovementRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.promotion.PromotionTargetReference;
import com.tpverp.backend.promotion.PromotionTargetRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    @Mock private CurrentOrganization organization;
    @Mock private StoreTaxRepository taxRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private FamilyRepository familyRepository;
    @Mock private SubfamilyRepository subfamilyRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductIdentifierRepository identifierRepository;
    @Mock private ProductPriceHistoryRepository priceHistoryRepository;
    @Mock private StockLevelRepository stockRepository;
    @Mock private StockMovementRepository movementRepository;
    @Mock private PromotionTargetRepository promotionTargetRepository;
    @Mock private ProductPriceRuleRepository productPriceRuleRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private Company company;
    @Mock private Store store;

    private CatalogService service;
    private final UUID storeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(store.getId()).thenReturn(storeId);
        lenient().when(organization.currentStore()).thenReturn(store);
        lenient().when(identifierRepository.findByStoreIdAndValor(any(), any()))
                .thenReturn(Optional.empty());
        service = new CatalogService(
                organization, taxRepository, warehouseRepository, familyRepository,
                subfamilyRepository, productRepository, identifierRepository,
                priceHistoryRepository, stockRepository, movementRepository,
                Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void listsCatalogOnlyForAuthenticatedStoreWhenTwoStoresExist() {
        var authenticatedStore = org.mockito.Mockito.mock(Store.class);
        var firstStoreId = UUID.randomUUID();
        var authenticatedStoreId = UUID.randomUUID();
        when(authenticatedStore.getId()).thenReturn(authenticatedStoreId);
        when(organization.currentStore()).thenReturn(authenticatedStore);
        var firstStoreTax = new StoreTax(firstStoreId, new BigDecimal("21"), false);
        var authenticatedTax = new StoreTax(
                authenticatedStoreId, new BigDecimal("7"), false);
        when(taxRepository.findByStoreIdOrderByPorcentaje(any()))
                .thenAnswer(invocation -> firstStoreId.equals(invocation.getArgument(0))
                        ? List.of(firstStoreTax)
                        : List.of(authenticatedTax));

        assertThat(service.taxes()).containsExactly(authenticatedTax);
        verify(taxRepository).findByStoreIdOrderByPorcentaje(authenticatedStoreId);
    }

    @Test
    void validatesBulkExportCodesInBulkAndRejectsAFalseOrForeignCode() {
        Family family = Family.general(storeId);
        when(familyRepository.findByStoreIdAndIdIn(storeId, List.of(family.getId())))
                .thenReturn(List.of(family));

        service.validateBulkExportCodes(
                Map.of(family.getId().toString(), "000"), Map.of());
        assertThatThrownBy(() -> service.validateBulkExportCodes(
                Map.of(family.getId().toString(), "999"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("familyCodes");
        verify(familyRepository, org.mockito.Mockito.times(2))
                .findByStoreIdAndIdIn(storeId, List.of(family.getId()));
    }

    @Test
    void nextFamilyCodeSkipsActiveAndReservedCodes() {
        Family general = Family.general(storeId);
        Family used = new Family(storeId, "Bebidas", false);
        used.assignCode("001");
        when(familyRepository.findByStoreIdOrderByFamilyCodeAscIdAsc(storeId))
                .thenReturn(List.of(general, used));
        when(familyRepository.findReservedFamilyCodes(storeId)).thenReturn(List.of("002"));

        assertThat(service.nextFamilyCode()).isEqualTo("003");
    }

    @Test
    void createsManualFamilyCodeAndUsesItAsTheNewLegacyAlias() {
        Family general = Family.general(storeId);
        when(familyRepository.existsByStoreIdAndNombreIgnoreCase(storeId, "BEBIDAS")).thenReturn(false);
        when(familyRepository.findByStoreIdOrderByFamilyCodeAscIdAsc(storeId))
                .thenReturn(List.of(general));
        when(familyRepository.findReservedFamilyCodes(storeId)).thenReturn(List.of());
        when(familyRepository.save(any(Family.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Family created = service.createFamily(" bebidas ", "007");

        assertThat(created.getFamilyCode()).isEqualTo("007");
        assertThat(created.getFamilyId()).isEqualTo("007");
    }

    @Test
    void resolvesStoreScopedCompositeCodeIntoNestedFamilyAndSubfamilyReferences() {
        Family family = new Family(storeId, "Bebidas", false);
        family.assignCode("007");
        Subfamily child = new Subfamily(family.getId(), "Cafe");
        child.assignCode("007", "012");
        when(subfamilyRepository.findByStoreIdAndSubfamilyCode(storeId, "007012"))
                .thenReturn(Optional.of(child));
        when(familyRepository.findById(family.getId())).thenReturn(Optional.of(family));

        var resolved = service.resolve(" 007012 ");

        assertThat(resolved.family().familyCode()).isEqualTo("007");
        assertThat(resolved.subfamily().subfamilyCode()).isEqualTo("007012");
        assertThat(resolved.subfamily().subfamilySuffix()).isEqualTo("012");
    }

    @Test
    void operationalResolverRejectsLegacyAliasesAndInternalUuids() {
        assertThatThrownBy(() -> service.resolve("LEGACY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3 o 6 digitos");
        assertThatThrownBy(() -> service.resolve(UUID.randomUUID().toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3 o 6 digitos");
    }

    @Test
    void renamePreservesLegacyAliases() {
        Family general = Family.general(storeId);
        Family first = new Family(storeId, "Primera", false);
        first.assignCode("007");
        Family second = new Family(storeId, "Segunda", false);
        second.assignCode("008");
        when(familyRepository.findById(first.getId())).thenReturn(Optional.of(first));
        service.renameFamily(first.getId(), "Renombrada");
        assertThat(first.getFamilyId()).isEqualTo("007");
        assertThat(first.getFamilyCode()).isEqualTo("007");

    }

    @Test
    void requiresExplicitProductConfirmationBeforeFamilyCleanup() {
        Family general = Family.general(storeId);
        Family family = new Family(storeId, "Bebidas", false);
        family.assignCode("007");
        when(familyRepository.findById(family.getId())).thenReturn(Optional.of(family));
        when(subfamilyRepository.findByFamilyIdOrderBySubfamilySuffixAscSubfamilyCodeAscIdAsc(family.getId()))
                .thenReturn(List.of());
        when(productRepository.countByFamilyId(family.getId())).thenReturn(3L, 3L, 0L);

        assertThatThrownBy(() -> service.deleteFamily(family.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("confirma");
        verify(familyRepository, never()).delete(any(Family.class));

        when(familyRepository.findByStoreIdAndPredeterminadaTrue(storeId))
                .thenReturn(Optional.of(general));
        service.deleteFamily(family.getId(), true);
        verify(productRepository).reassignFamilyToGeneral(family.getId(), general.getId());
        verify(familyRepository).delete(family);
    }

    @Test
    void deleteImpactDeduplicatesOnePromotionAndRuleAcrossFamilyDescendants() {
        CatalogService serviceWithDependencies = new CatalogService(
                organization, taxRepository, warehouseRepository, familyRepository,
                subfamilyRepository, productRepository, identifierRepository,
                priceHistoryRepository, stockRepository, movementRepository,
                promotionTargetRepository, productPriceRuleRepository, storeRepository,
                Clock.systemUTC());
        Family family = new Family(storeId, "Bebidas", false);
        family.assignCode("007");
        Subfamily child = new Subfamily(family.getId(), "Cafe");
        child.assignCode("007", "001");
        UUID promotionId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        var promotionFamily = org.mockito.Mockito.mock(PromotionTargetReference.class);
        var promotionChild = org.mockito.Mockito.mock(PromotionTargetReference.class);
        when(promotionFamily.getPromotionId()).thenReturn(promotionId);
        when(promotionFamily.getPromotionName()).thenReturn("Oferta");
        when(promotionFamily.getType()).thenReturn(com.tpverp.backend.promotion.PromotionTargetType.FAMILY);
        when(promotionFamily.getTargetId()).thenReturn(family.getId());
        when(promotionChild.getPromotionId()).thenReturn(promotionId);
        when(promotionChild.getPromotionName()).thenReturn("Oferta");
        when(promotionChild.getType()).thenReturn(com.tpverp.backend.promotion.PromotionTargetType.SUBFAMILY);
        when(promotionChild.getTargetId()).thenReturn(child.getId());
        var rule1 = org.mockito.Mockito.mock(ProductPriceRuleReference.class);
        var rule2 = org.mockito.Mockito.mock(ProductPriceRuleReference.class);
        when(rule1.getRuleId()).thenReturn(ruleId);
        when(rule1.getRuleName()).thenReturn("Regla");
        when(rule2.getRuleId()).thenReturn(ruleId);
        when(rule2.getRuleName()).thenReturn("Regla");
        when(familyRepository.findById(family.getId())).thenReturn(Optional.of(family));
        when(subfamilyRepository.findByFamilyIdOrderBySubfamilySuffixAscSubfamilyCodeAscIdAsc(family.getId()))
                .thenReturn(List.of(child));
        when(productRepository.countByFamilyId(family.getId())).thenReturn(0L);
        when(promotionTargetRepository.findFamilyOrSubfamilyReferences(any(), any()))
                .thenReturn(List.of(promotionFamily, promotionChild));
        when(productPriceRuleRepository.findFamilyOrSubfamilyReferences(any(), any()))
                .thenReturn(List.of(rule1, rule2));
        when(organization.currentCompany()).thenReturn(company);
        when(company.getId()).thenReturn(UUID.randomUUID());

        var impact = serviceWithDependencies.familyDeleteImpact(family.getId());

        assertThat(impact.promotionCount()).isEqualTo(1);
        assertThat(impact.priceRuleCount()).isEqualTo(1);
        assertThat(impact.dependencies()).hasSize(2);
        assertThat(impact.isBlocked()).isTrue();
    }

    @Test
    void locksAndRevalidatesDependenciesImmediatelyBeforeDeletingAFamily() {
        CatalogService serviceWithDependencies = new CatalogService(
                organization, taxRepository, warehouseRepository, familyRepository,
                subfamilyRepository, productRepository, identifierRepository,
                priceHistoryRepository, stockRepository, movementRepository,
                promotionTargetRepository, productPriceRuleRepository, storeRepository,
                Clock.systemUTC());
        UUID companyId = UUID.randomUUID();
        Family general = Family.general(storeId);
        Family family = new Family(storeId, "Bebidas", false);
        family.assignCode("007");
        var lateReference = org.mockito.Mockito.mock(PromotionTargetReference.class);
        when(lateReference.getPromotionId()).thenReturn(UUID.randomUUID());
        when(lateReference.getPromotionName()).thenReturn("Oferta concurrente");
        when(lateReference.getType())
                .thenReturn(com.tpverp.backend.promotion.PromotionTargetType.FAMILY);
        when(lateReference.getTargetId()).thenReturn(family.getId());
        when(organization.currentCompany()).thenReturn(company);
        when(company.getId()).thenReturn(companyId);
        when(storeRepository.findByIdForUpdate(storeId)).thenReturn(Optional.of(store));
        when(familyRepository.findByIdForUpdate(family.getId())).thenReturn(Optional.of(family));
        when(familyRepository.findByStoreIdAndPredeterminadaTrue(storeId))
                .thenReturn(Optional.of(general));
        when(subfamilyRepository.findByFamilyIdOrderBySubfamilySuffixAscSubfamilyCodeAscIdAsc(family.getId()))
                .thenReturn(List.of());
        when(productRepository.countByFamilyId(family.getId())).thenReturn(0L);
        when(promotionTargetRepository.findFamilyOrSubfamilyReferences(companyId, List.of(family.getId())))
                .thenReturn(List.of(), List.of(lateReference));
        when(productPriceRuleRepository.findFamilyOrSubfamilyReferences(any(), any()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> serviceWithDependencies.deleteFamily(family.getId(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aparecieron referencias");

        verify(storeRepository).findByIdForUpdate(storeId);
        verify(productRepository).reassignFamilyToGeneral(family.getId(), general.getId());
        verify(familyRepository, never()).delete(family);
    }

    @Test
    void refusesFamilyDeleteWhenProductsRemainAfterBulkReassignment() {
        CatalogService serviceWithStoreMutex = new CatalogService(
                organization, taxRepository, warehouseRepository, familyRepository,
                subfamilyRepository, productRepository, identifierRepository,
                priceHistoryRepository, stockRepository, movementRepository,
                null, null, storeRepository, Clock.systemUTC());
        Family general = Family.general(storeId);
        Family family = new Family(storeId, "Bebidas", false);
        family.assignCode("007");
        when(storeRepository.findByIdForUpdate(storeId)).thenReturn(Optional.of(store));
        when(familyRepository.findByIdForUpdate(family.getId())).thenReturn(Optional.of(family));
        when(familyRepository.findByStoreIdAndPredeterminadaTrue(storeId))
                .thenReturn(Optional.of(general));
        when(subfamilyRepository.findByFamilyIdOrderBySubfamilySuffixAscSubfamilyCodeAscIdAsc(
                family.getId())).thenReturn(List.of());
        when(productRepository.countByFamilyId(family.getId())).thenReturn(1L, 1L);

        assertThatThrownBy(() -> serviceWithStoreMutex.deleteFamily(family.getId(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aparecieron referencias");

        verify(productRepository).reassignFamilyToGeneral(family.getId(), general.getId());
        verify(familyRepository, never()).delete(family);
    }

    @Test
    void rejectsPriceRuleReferencesOutsideTheAuthenticatedStore() {
        UUID missingFamilyId = UUID.randomUUID();
        ProductPriceRuleForm.Definition form = new ProductPriceRuleForm.Definition(
                ProductPriceRuleForm.Scope.FAMILY,
                List.of(new ProductPriceRuleForm.ReferenceCondition(
                        ProductPriceRuleForm.ReferenceField.FAMILY,
                        ProductPriceRuleForm.SetComparator.IN,
                        List.of(missingFamilyId))),
                List.of(new ProductPriceRuleForm.FixedPriceAction(
                        ProductPriceRuleForm.PriceField.SALE_PRICE,
                        new BigDecimal("2.50"))));
        when(familyRepository.findByStoreIdAndIdIn(storeId, java.util.Set.of(missingFamilyId)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.validatePriceRuleCatalogReferences(List.of(form)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tienda actual");
    }

    @Test
    void onlyReturnsActiveTaxesForProductSelection() {
        var active = new StoreTax(storeId, new BigDecimal("7"), false);
        var inactive = new StoreTax(storeId, new BigDecimal("21"), false);
        inactive.deactivate();
        when(taxRepository.findByStoreIdOrderByPorcentaje(storeId)).thenReturn(List.of(active, inactive));

        assertThat(service.selectableTaxes()).containsExactly(active);
    }

    @Test
    void listsSubfamiliesForFamily() {
        var family = Family.general(storeId);
        var subfamily = new Subfamily(family.getId(), "Cafe");
        when(familyRepository.findById(family.getId())).thenReturn(Optional.of(family));
        when(subfamilyRepository.findByFamilyIdOrderBySubfamilySuffixAscSubfamilyCodeAscIdAsc(family.getId())).thenReturn(List.of(subfamily));

        assertThat(service.subfamilies(family.getId())).containsExactly(subfamily);
    }

    @Test
    void updatesTaxPercentageWithoutAllowingDuplicates() {
        var tax = new StoreTax(storeId, new BigDecimal("7"), false);
        when(taxRepository.findById(tax.getId())).thenReturn(Optional.of(tax));
        when(taxRepository.findByStoreIdAndPorcentaje(storeId, new BigDecimal("10")))
                .thenReturn(Optional.empty());

        var updated = service.updateTax(tax.getId(), new BigDecimal("10"));

        assertThat(updated.getPercentage()).isEqualByComparingTo("10");
    }

    @Test
    void deletesOnlyNonDefaultTaxNotUsedByProducts() {
        var tax = new StoreTax(storeId, new BigDecimal("4"), false);
        when(taxRepository.findById(tax.getId())).thenReturn(Optional.of(tax));
        when(productRepository.existsByTaxId(tax.getId())).thenReturn(false);

        service.deleteTax(tax.getId());

        verify(taxRepository).delete(tax);
    }

    @Test
    void cannotDeactivateTaxUsedByProducts() {
        var tax = new StoreTax(storeId, new BigDecimal("4"), false);
        when(taxRepository.findById(tax.getId())).thenReturn(Optional.of(tax));
        when(productRepository.existsByTaxId(tax.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.setTaxActive(tax.getId(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("utilizado por productos");
        assertThat(tax.isActive()).isTrue();
    }

    @Test
    void rejectsCrossIdentifierCollisionWhenCreatingProduct() {
        var request = productRequest(" ABC ", "EAN");
        when(familyRepository.findById(request.familyId())).thenReturn(Optional.of(Family.general(storeId)));
        when(taxRepository.findById(request.taxId()))
                .thenReturn(Optional.of(new StoreTax(storeId, new BigDecimal("7"), true)));
        when(identifierRepository.findByStoreIdAndValor(storeId, "ABC"))
                .thenReturn(Optional.of(new ProductIdentifier(
                        storeId, UUID.randomUUID(), IdentifierType.CODIGO_BARRAS, "ABC")));

        assertThatThrownBy(() -> service.createProduct(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identificador");
    }

    @Test
    void createsProductWithPricesAndOffer() {
        var request = productRequest("ABC", "EAN");
        when(familyRepository.findById(request.familyId())).thenReturn(Optional.of(Family.general(storeId)));
        when(taxRepository.findById(request.taxId()))
                .thenReturn(Optional.of(new StoreTax(storeId, new BigDecimal("7"), true)));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var product = service.createProduct(request);

        assertThat(product.identifier(IdentifierType.CODIGO)).isEqualTo("ABC");
        assertThat(product.identifier(IdentifierType.CODIGO_BARRAS)).isEqualTo("EAN");
        assertThat(product.price(PriceTier.VENTA)).isEqualByComparingTo("2.50");
        assertThat(product.isOfferActive()).isTrue();
        assertThat(product.isActive()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductPriceHistory>> history = ArgumentCaptor.forClass(List.class);
        verify(priceHistoryRepository).saveAll(history.capture());
        assertThat(history.getValue()).hasSize(3);
    }

    @Test
    void createsProductWhenCodeAndBarcodeAreTheSameIdentifier() {
        var request = productRequest("0", "0");
        when(familyRepository.findById(request.familyId())).thenReturn(Optional.of(Family.general(storeId)));
        when(taxRepository.findById(request.taxId()))
                .thenReturn(Optional.of(new StoreTax(storeId, new BigDecimal("7"), true)));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var product = service.createProduct(request);

        assertThat(product.identifier(IdentifierType.CODIGO)).isEqualTo("0");
        assertThat(product.identifier(IdentifierType.CODIGO_BARRAS)).isEqualTo("0");
    }

    @Test
    void updatesProductWhenCodeAndBarcodeAreTheSameIdentifier() {
        var request = productRequest("2", "2");
        var product = new Product(
                storeId, request.familyId(), null, request.taxId(), ProductType.UNIT,
                DiscountType.NORMAL, "Agua", null, null, BigDecimal.ZERO, true);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(familyRepository.findById(request.familyId())).thenReturn(Optional.of(Family.general(storeId)));
        when(taxRepository.findById(request.taxId()))
                .thenReturn(Optional.of(new StoreTax(storeId, new BigDecimal("21"), true)));

        var updated = service.updateProduct(product.getId(), request);

        assertThat(updated.identifier(IdentifierType.CODIGO)).isEqualTo("2");
        assertThat(updated.identifier(IdentifierType.CODIGO_BARRAS)).isEqualTo("2");
    }

    @Test
    void updateWithoutActiveFieldPreservesInactiveStateForLegacyClients() {
        var request = productRequest("P-1", null);
        var product = new Product(
                storeId, request.familyId(), null, request.taxId(), ProductType.UNIT,
                DiscountType.NORMAL, "Producto", null, null, BigDecimal.ZERO, true);
        product.deactivate();
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(familyRepository.findById(request.familyId())).thenReturn(Optional.of(Family.general(storeId)));
        when(taxRepository.findById(request.taxId()))
                .thenReturn(Optional.of(new StoreTax(storeId, new BigDecimal("7"), true)));

        service.updateProduct(product.getId(), request);

        assertThat(product.isActive()).isFalse();
    }

    @Test
    void updateWithoutSerialFieldPreservesRequiredSerialPolicyForLegacyClients() {
        var request = productRequest("P-SERIAL", null);
        var product = new Product(
                storeId, request.familyId(), null, request.taxId(), ProductType.UNIT,
                DiscountType.NORMAL, "Producto", null, null, BigDecimal.ZERO, true);
        product.configureSerialNumberTracking(true);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(familyRepository.findById(request.familyId())).thenReturn(Optional.of(Family.general(storeId)));
        when(taxRepository.findById(request.taxId()))
                .thenReturn(Optional.of(new StoreTax(storeId, new BigDecimal("7"), true)));

        service.updateProduct(product.getId(), request);

        assertThat(product.isRequiresSerialNumber()).isTrue();
    }

    @Test
    void legacyUpdateChangingUnitSerialProductToWeightClearsSerialPolicy() {
        var base = productRequest("P-SERIAL-WEIGHT", null);
        var request = new CatalogService.ProductRequest(
                base.familyId(), base.subfamilyId(), base.taxId(), ProductType.WEIGHT,
                base.discountType(), base.priceUseMode(), base.name(), base.description(),
                base.comments(), base.purchasePrice(), base.taxesIncluded(), base.code(),
                base.barcode(), base.barcode2(), base.salePrice(), base.memberPrice(),
                base.wholesalePrice(), base.offerPrice(), base.offerDiscountPercent(),
                base.purchaseDiscountPercent(), base.offerActive(), base.offerFrom(),
                base.offerUntil(), base.stockMin(), base.stockMax(), base.packageQuantity(),
                base.active(), null);
        var product = new Product(
                storeId, request.familyId(), null, request.taxId(), ProductType.UNIT,
                DiscountType.NORMAL, "Producto", null, null, BigDecimal.ZERO, true);
        product.configureSerialNumberTracking(true);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(familyRepository.findById(request.familyId())).thenReturn(Optional.of(Family.general(storeId)));
        when(taxRepository.findById(request.taxId()))
                .thenReturn(Optional.of(new StoreTax(storeId, new BigDecimal("7"), true)));

        service.updateProduct(product.getId(), request);

        assertThat(product.isRequiresSerialNumber()).isFalse();
    }

    @Test
    void fullProductUpdatePersistsActiveState() {
        var base = productRequest("P-2", null);
        var request = withActive(base, false);
        var product = new Product(
                storeId, request.familyId(), null, request.taxId(), ProductType.UNIT,
                DiscountType.NORMAL, "Producto", null, null, BigDecimal.ZERO, true);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(familyRepository.findById(request.familyId())).thenReturn(Optional.of(Family.general(storeId)));
        when(taxRepository.findById(request.taxId()))
                .thenReturn(Optional.of(new StoreTax(storeId, new BigDecimal("7"), true)));

        service.updateProduct(product.getId(), request);

        assertThat(product.isActive()).isFalse();
    }

    @Test
    void patchStyleServiceOperationChangesOnlyActiveState() {
        var product = new Product(
                storeId, UUID.randomUUID(), null, UUID.randomUUID(),
                "Producto", null, BigDecimal.ZERO, true);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));

        var updated = service.setProductActive(product.getId(), false);

        assertThat(updated.isActive()).isFalse();
        assertThat(updated.getName()).isEqualTo("PRODUCTO");
    }

    @Test
    void createsProductWhenBarcodeIsPresentAndCodeIsEmpty() {
        var request = productRequest(null, "EAN13");
        when(familyRepository.findById(request.familyId())).thenReturn(Optional.of(Family.general(storeId)));
        when(taxRepository.findById(request.taxId()))
                .thenReturn(Optional.of(new StoreTax(storeId, new BigDecimal("7"), true)));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var product = service.createProduct(request);

        assertThat(product.getCode()).isNull();
        assertThat(product.getBarcode()).isEqualTo("EAN13");
    }

    @Test
    void createsProductWithSecondaryBarcode() {
        var base = productRequest("ABC", "EAN13");
        var request = new CatalogService.ProductRequest(
                base.familyId(), null, base.taxId(), ProductType.UNIT, DiscountType.NORMAL,
                PriceUseMode.OFFER_PRICE,
                "Producto", null, null, BigDecimal.ZERO, true, "ABC", "EAN13", "EAN14",
                new BigDecimal("2.50"), null, null, new BigDecimal("1.50"),
                null, new BigDecimal("5.00"), true, java.time.LocalDate.of(2026, 6, 1), null);
        when(familyRepository.findById(request.familyId())).thenReturn(Optional.of(Family.general(storeId)));
        when(taxRepository.findById(request.taxId()))
                .thenReturn(Optional.of(new StoreTax(storeId, new BigDecimal("7"), true)));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var product = service.createProduct(request);

        assertThat(product.getBarcode2()).isEqualTo("EAN14");
        assertThat(product.getPurchaseDiscountPercent()).isEqualByComparingTo("5.00");
    }

    @Test
    void createsProductWithPersistedPriceUseModeAndOfferDiscountPercent() {
        var base = productRequest("OFFERDISC", null);
        var request = new CatalogService.ProductRequest(
                base.familyId(), null, base.taxId(), ProductType.UNIT, DiscountType.DISCOUNT_PRICE,
                PriceUseMode.OFFER_DISCOUNT,
                "Producto", null, null, BigDecimal.ZERO, true, "OFFERDISC", null, null,
                new BigDecimal("10.00"), null, null, new BigDecimal("8.50"),
                new BigDecimal("15.00"), null,
                true, java.time.LocalDate.of(2026, 7, 1), java.time.LocalDate.of(2026, 7, 31));
        when(familyRepository.findById(request.familyId())).thenReturn(Optional.of(Family.general(storeId)));
        when(taxRepository.findById(request.taxId()))
                .thenReturn(Optional.of(new StoreTax(storeId, new BigDecimal("7"), true)));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var product = service.createProduct(request);

        assertThat(product.getPriceUseMode()).isEqualTo(PriceUseMode.OFFER_DISCOUNT);
        assertThat(product.getOfferDiscountPercent()).isEqualByComparingTo("15.00");
        assertThat(product.getOfferPrice()).isEqualByComparingTo("8.50");
        assertThat(product.isOfferActive()).isTrue();
        assertThat(product.getOfferFrom()).isEqualTo(java.time.LocalDate.of(2026, 7, 1));
        assertThat(product.getOfferUntil()).isEqualTo(java.time.LocalDate.of(2026, 7, 31));
    }

    @Test
    void createsProductWithNoDiscountLockAndSalePriceMode() {
        var base = productRequest("NODISC", null);
        var request = new CatalogService.ProductRequest(
                base.familyId(), null, base.taxId(), ProductType.UNIT, DiscountType.NONE,
                PriceUseMode.OFFER_PRICE,
                "Producto", null, null, BigDecimal.ZERO, true, "NODISC", null, null,
                new BigDecimal("10.00"), null, null, new BigDecimal("8.00"),
                null, null, true, java.time.LocalDate.of(2026, 7, 1),
                java.time.LocalDate.of(2026, 7, 31));
        when(familyRepository.findById(request.familyId())).thenReturn(Optional.of(Family.general(storeId)));
        when(taxRepository.findById(request.taxId()))
                .thenReturn(Optional.of(new StoreTax(storeId, new BigDecimal("7"), true)));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var product = service.createProduct(request);

        assertThat(product.getPriceUseMode()).isEqualTo(PriceUseMode.NORMAL);
        assertThat(product.getDiscountType()).isEqualTo(DiscountType.NONE);
        assertThat(product.isOfferActive()).isFalse();
    }

    @Test
    void rejectsSerialNumberTrackingForNonUnitProducts() {
        var base = productRequest("SERIAL-WEIGHT", null);
        var request = new CatalogService.ProductRequest(
                base.familyId(), null, base.taxId(), ProductType.WEIGHT, DiscountType.NORMAL,
                PriceUseMode.NORMAL, "Producto pesado", null, null, BigDecimal.ZERO, true,
                "SERIAL-WEIGHT", null, null, new BigDecimal("2.50"), null, null, null,
                null, null, false, null, null, null, null, null, null, true);
        when(familyRepository.findById(request.familyId())).thenReturn(Optional.of(Family.general(storeId)));
        when(taxRepository.findById(request.taxId()))
                .thenReturn(Optional.of(new StoreTax(storeId, new BigDecimal("7"), true)));

        assertThatThrownBy(() -> service.createProduct(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message.product.serial_number_requires_unit");
    }

    @Test
    void discountPriceRequiresActiveOfferData() {
        var base = productRequest("ABC", null);
        var request = new CatalogService.ProductRequest(
                base.familyId(), null, base.taxId(), ProductType.UNIT, DiscountType.DISCOUNT_PRICE,
                PriceUseMode.OFFER_PRICE,
                "Producto", null, null, BigDecimal.ZERO, true, "ABC", null, null,
                new BigDecimal("2.50"), null, null, null,
                null, null, false, null, null);
        when(familyRepository.findById(request.familyId())).thenReturn(Optional.of(Family.general(storeId)));
        when(taxRepository.findById(request.taxId()))
                .thenReturn(Optional.of(new StoreTax(storeId, new BigDecimal("7"), true)));

        assertThatThrownBy(() -> service.createProduct(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message.product.discount_price_requires_offer");
    }

    @Test
    void updateStoresOnlyChangedPriceHistory() {
        var initial = productRequest("ABC", null);
        var product = new Product(
                storeId, initial.familyId(), null, initial.taxId(),
                "Producto", null, BigDecimal.ZERO, true);
        product.replaceIdentifier(IdentifierType.CODIGO, "ABC");
        product.setPrice(PriceTier.VENTA, new BigDecimal("2.50"));
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(familyRepository.findById(initial.familyId()))
                .thenReturn(Optional.of(Family.general(storeId)));
        when(taxRepository.findById(initial.taxId()))
                .thenReturn(Optional.of(new StoreTax(storeId, new BigDecimal("7"), true)));
        var changed = new CatalogService.ProductRequest(
                initial.familyId(), null, initial.taxId(), ProductType.UNIT, DiscountType.NORMAL,
                PriceUseMode.NORMAL,
                "Producto", null, null, new BigDecimal("1.00"), true, "ABC", null, null,
                new BigDecimal("3.00"), null, null, null,
                null, null, false, null, null);

        service.updateProduct(product.getId(), changed);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductPriceHistory>> history = ArgumentCaptor.forClass(List.class);
        verify(priceHistoryRepository).saveAll(history.capture());
        assertThat(history.getValue()).hasSize(2);
    }

    @Test
    void updateRemovesBarcodeWhenItIsOmitted() {
        var request = productRequest("ABC", null);
        var product = new Product(
                storeId, request.familyId(), null, request.taxId(),
                "Producto", null, BigDecimal.ZERO, true);
        product.replaceIdentifier(IdentifierType.CODIGO, "ABC");
        product.replaceIdentifier(IdentifierType.CODIGO_BARRAS, "EAN");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(familyRepository.findById(request.familyId()))
                .thenReturn(Optional.of(Family.general(storeId)));
        when(taxRepository.findById(request.taxId()))
                .thenReturn(Optional.of(new StoreTax(
                        storeId, new BigDecimal("7"), true)));

        service.updateProduct(product.getId(), request);

        assertThat(product.getBarcode()).isNull();
    }

    @Test
    void defaultWarehouseCannotBeDeleted() {
        var general = Warehouse.general(storeId);
        when(warehouseRepository.findById(general.getId())).thenReturn(Optional.of(general));

        assertThatThrownBy(() -> service.deleteWarehouse(general.getId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deletesEmptySecondaryWarehouse() {
        var warehouse = new Warehouse(storeId, "SECUNDARIO");
        when(warehouseRepository.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(stockRepository.sumQuantityByWarehouseId(warehouse.getId())).thenReturn(BigDecimal.ZERO);

        service.deleteWarehouse(warehouse.getId());

        verify(warehouseRepository).delete(warehouse);
    }

    @Test
    void bulkUpdateRejectsEveryStaleVersionBeforeChangingProducts() {
        CatalogService.ProductRequest request = productRequest("ABC", null);
        Product product = new Product(
                storeId, request.familyId(), null, request.taxId(), ProductType.UNIT,
                DiscountType.NORMAL, "Original", null, null, BigDecimal.ZERO, true);
        when(productRepository.findAllByStoreIdAndIdIn(
                org.mockito.ArgumentMatchers.eq(storeId), any())).thenReturn(List.of(product));

        assertThatThrownBy(() -> service.updateProducts(List.of(
                new CatalogService.BulkProductUpdate(product.getId(), 1L, request))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicto de version")
                .hasMessageContaining(product.getId().toString());

        assertThat(product.getName()).isEqualTo("ORIGINAL");
        verify(familyRepository, never()).findById(any());
    }

    private CatalogService.ProductRequest productRequest(String code, String barcode) {
        return new CatalogService.ProductRequest(
                UUID.randomUUID(), null, UUID.randomUUID(), ProductType.UNIT, DiscountType.NORMAL,
                PriceUseMode.OFFER_PRICE,
                "Producto", null, null, BigDecimal.ZERO, true, code, barcode, null,
                new BigDecimal("2.50"), null, null, new BigDecimal("1.50"),
                null, null, true, java.time.LocalDate.of(2026, 6, 1), null);
    }

    private static CatalogService.ProductRequest withActive(
            CatalogService.ProductRequest value, boolean active) {
        return new CatalogService.ProductRequest(
                value.familyId(), value.subfamilyId(), value.taxId(), value.productType(),
                value.discountType(), value.priceUseMode(), value.name(), value.description(),
                value.comments(), value.purchasePrice(), value.taxesIncluded(), value.code(),
                value.barcode(), value.barcode2(), value.salePrice(), value.memberPrice(),
                value.wholesalePrice(), value.offerPrice(), value.offerDiscountPercent(),
                value.purchaseDiscountPercent(), value.offerActive(), value.offerFrom(),
                value.offerUntil(), value.stockMin(), value.stockMax(), value.packageQuantity(), active);
    }
}
