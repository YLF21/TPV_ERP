package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.inventory.StockLevelRepository;
import com.tpverp.backend.inventory.StockMovementRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FamilyProductCatalogServiceTest {
    @Mock CurrentOrganization organization;
    @Mock Store store;
    @Mock StoreTaxRepository taxRepository;
    @Mock WarehouseRepository warehouseRepository;
    @Mock FamilyRepository familyRepository;
    @Mock SubfamilyRepository subfamilyRepository;
    @Mock ProductRepository productRepository;
    @Mock FamilyProductPageRepository familyProductPageRepository;
    @Mock ProductIdentifierRepository identifierRepository;
    @Mock ProductPriceHistoryRepository priceHistoryRepository;
    @Mock StockLevelRepository stockRepository;
    @Mock StockMovementRepository movementRepository;
    @Mock StoreRepository storeRepository;
    @Mock AuditService auditService;

    private final UUID storeId = UUID.randomUUID();
    private CatalogService service;

    @BeforeEach
    void setUp() {
        lenient().when(store.getId()).thenReturn(storeId);
        lenient().when(organization.currentStore()).thenReturn(store);
        lenient().when(storeRepository.findByIdForUpdate(storeId)).thenReturn(Optional.of(store));
        service = new CatalogService(organization, taxRepository, warehouseRepository,
                familyRepository, subfamilyRepository, productRepository, identifierRepository,
                priceHistoryRepository, stockRepository, movementRepository, null, null,
                storeRepository, Clock.systemUTC());
        service.setAuditService(auditService);
        service.setFamilyProductPageRepository(familyProductPageRepository);
    }

    @Test
    void familyProductPageRequiresExactlyOneScopeAndUsesBoundedDefaultOrder() {
        Family family = new Family(storeId, "Bebidas", false);
        family.assignCode("007");
        var row = familyProductRow(
                UUID.randomUUID(), family.getId(), null, "A-1", "8412345678901",
                "CAFE", new BigDecimal("2.10"), false, false, "cafe");
        when(familyRepository.findById(family.getId())).thenReturn(Optional.of(family));
        when(familyProductPageRepository.findPage(
                storeId, FamilyProductPageRepository.ScopeKind.FAMILY, family.getId(),
                "name", "asc", null, 51))
                .thenReturn(List.of(row));

        var page = service.familyProducts(family.getId(), null, 50, null);

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.code()).isEqualTo("A-1");
            assertThat(item.barcode()).isEqualTo("8412345678901");
            assertThat(item.active()).isFalse();
        });
        verify(familyProductPageRepository).findPage(
                storeId, FamilyProductPageRepository.ScopeKind.FAMILY, family.getId(),
                "name", "asc", null, 51);
    }

    @Test
    void familyProductCursorCarriesTypedOrderAndResumesInsideTies() {
        Family family = new Family(storeId, "Bebidas", false);
        family.assignCode("007");
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000020");
        var first = familyProductRow(
                firstId, family.getId(), null, "A-1", "", "CAFE",
                new BigDecimal("2.10"), true, false, "2.10");
        var lookAhead = familyProductRow(
                UUID.fromString("00000000-0000-0000-0000-000000000010"),
                family.getId(), null, "A-2", "", "TE",
                new BigDecimal("2.10"), true, false, "2.10");
        when(familyRepository.findById(family.getId())).thenReturn(Optional.of(family));
        when(familyProductPageRepository.findPage(
                storeId, FamilyProductPageRepository.ScopeKind.FAMILY, family.getId(),
                "salePrice", "desc", null, 2))
                .thenReturn(List.of(first, lookAhead));

        var firstPage = service.familyProducts(
                family.getId(), null, 1, null, "salePrice", "DESC");

        assertThat(firstPage.items()).extracting(FamilyProductView::id)
                .containsExactly(firstId);
        assertThat(firstPage.nextCursor()).isNotBlank();
        var cursor = ArgumentCaptor.forClass(
                FamilyProductPageRepository.FamilyProductPageCursor.class);
        when(familyProductPageRepository.findPage(
                eq(storeId), eq(FamilyProductPageRepository.ScopeKind.FAMILY),
                eq(family.getId()), eq("salePrice"), eq("desc"), cursor.capture(), eq(2)))
                .thenReturn(List.of(lookAhead));

        service.familyProducts(
                family.getId(), null, 1, firstPage.nextCursor(), "salePrice", "desc");

        assertThat(cursor.getValue().nullSortValue()).isFalse();
        assertThat(cursor.getValue().value()).isEqualTo("2.10");
        assertThat(cursor.getValue().id()).isEqualTo(firstId);
    }

    @Test
    void familyProductCursorRejectsAChangedOrderOrScope() {
        Family firstFamily = new Family(storeId, "Bebidas", false);
        firstFamily.assignCode("007");
        Family secondFamily = new Family(storeId, "Comida", false);
        secondFamily.assignCode("008");
        var first = familyProductRow(
                UUID.randomUUID(), firstFamily.getId(), null, "A-1", "", "CAFE",
                BigDecimal.ONE, true, false, "A-1");
        var lookAhead = familyProductRow(
                UUID.randomUUID(), firstFamily.getId(), null, "A-2", "", "TE",
                BigDecimal.TEN, true, false, "A-2");
        when(familyRepository.findById(firstFamily.getId())).thenReturn(Optional.of(firstFamily));
        when(familyRepository.findById(secondFamily.getId())).thenReturn(Optional.of(secondFamily));
        when(familyProductPageRepository.findPage(
                storeId, FamilyProductPageRepository.ScopeKind.FAMILY, firstFamily.getId(),
                "code", "asc", null, 2))
                .thenReturn(List.of(first, lookAhead));
        String cursor = service.familyProducts(
                firstFamily.getId(), null, 1, null, "code", "asc").nextCursor();

        assertThatThrownBy(() -> service.familyProducts(
                firstFamily.getId(), null, 1, cursor, "name", "asc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursor");
        assertThatThrownBy(() -> service.familyProducts(
                firstFamily.getId(), null, 1, cursor, "code", "desc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursor");
        assertThatThrownBy(() -> service.familyProducts(
                secondFamily.getId(), null, 1, cursor, "code", "asc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursor");
    }

    @Test
    void familyProductCursorPreservesTheNullBucketAndValidatesSortInputs() {
        Family family = new Family(storeId, "Bebidas", false);
        family.assignCode("007");
        var missingPrice = familyProductRow(
                UUID.randomUUID(), family.getId(), null, "A-1", "", "CAFE",
                null, true, true, null);
        var lookAhead = familyProductRow(
                UUID.randomUUID(), family.getId(), null, "A-2", "", "TE",
                null, true, true, null);
        when(familyRepository.findById(family.getId())).thenReturn(Optional.of(family));
        when(familyProductPageRepository.findPage(
                storeId, FamilyProductPageRepository.ScopeKind.FAMILY, family.getId(),
                "salePrice", "asc", null, 2))
                .thenReturn(List.of(missingPrice, lookAhead));
        String cursorValue = service.familyProducts(
                family.getId(), null, 1, null, "salePrice", "asc").nextCursor();
        var cursor = ArgumentCaptor.forClass(
                FamilyProductPageRepository.FamilyProductPageCursor.class);
        when(familyProductPageRepository.findPage(
                eq(storeId), eq(FamilyProductPageRepository.ScopeKind.FAMILY),
                eq(family.getId()), eq("salePrice"), eq("asc"), cursor.capture(), eq(2)))
                .thenReturn(List.of());

        service.familyProducts(
                family.getId(), null, 1, cursorValue, "salePrice", "asc");

        assertThat(cursor.getValue().nullSortValue()).isTrue();
        assertThat(cursor.getValue().value()).isNull();
        assertThatThrownBy(() -> service.familyProducts(
                family.getId(), null, 25, null, "stock", "asc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sortBy");
        assertThatThrownBy(() -> service.familyProducts(
                family.getId(), null, 25, null, "name", "sideways"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sortDirection");
    }

    @Test
    void hierarchySearchIsStoreScopedBoundedAndAccentInsensitive() {
        FamilyHierarchySearchProjection row = org.mockito.Mockito.mock(FamilyHierarchySearchProjection.class);
        UUID familyId = UUID.randomUUID();
        UUID subfamilyId = UUID.randomUUID();
        when(row.getKind()).thenReturn("SUBFAMILY");
        when(row.getId()).thenReturn(subfamilyId);
        when(row.getFamilyId()).thenReturn(familyId);
        when(row.getSubfamilyId()).thenReturn(subfamilyId);
        when(row.getCode()).thenReturn("007012");
        when(row.getName()).thenReturn("CAFÉ");
        when(row.getFamilyCode()).thenReturn("007");
        when(row.getSuffix()).thenReturn("012");
        when(row.isDefaultFamily()).thenReturn(false);
        when(familyRepository.searchHierarchy(storeId, "CAFE", 4, null, null, null, 51))
                .thenReturn(List.of(row));

        var result = service.searchHierarchy(" café ", 50, null);

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo("SUBFAMILY");
            assertThat(item.code()).isEqualTo("007012");
            assertThat(item.familyCode()).isEqualTo("007");
        });
        verify(familyRepository).searchHierarchy(storeId, "CAFE", 4, null, null, null, 51);
    }

    @Test
    void hierarchySearchRejectsOneCodePointQueries() {
        assertThatThrownBy(() -> service.searchHierarchy("é", 50, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entre 2 y 100");
    }

    @Test
    void bulkMoveResolvesGeneralWhenDestinationIsOmittedAndChecksVersions() {
        Family general = Family.general(storeId);
        Product product = new Product(storeId, general.getId(), null, UUID.randomUUID(),
                ProductType.UNIT, DiscountType.NORMAL, "CAFE", null, null,
                BigDecimal.ZERO, true);
        when(familyRepository.findByStoreIdAndPredeterminadaTrue(storeId)).thenReturn(Optional.of(general));
        when(productRepository.findAllByStoreIdAndIdInForUpdate(any(), any()))
                .thenReturn(List.of(product));
        when(productRepository.moveClassification(any(), any(), any(), isNull()))
                .thenReturn(1);

        var result = service.moveProducts(new CatalogService.BulkMoveRequest(
                List.of(new CatalogService.MoveProductItem(product.getId(), 0L)), null, null));

        assertThat(result.updatedCount()).isEqualTo(1);
        assertThat(result.familyId()).isEqualTo(general.getId());
        verify(productRepository).moveClassification(any(), any(), any(), isNull());
        var details = ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditService).record(eq("PRODUCT_CLASSIFICATION_BULK_MOVED"),
                eq(AuditResult.EXITO), details.capture());
        @SuppressWarnings("unchecked")
        var changes = (List<java.util.Map<String, Object>>) details.getValue().get("changes");
        assertThat(changes).hasSize(1);
        @SuppressWarnings("unchecked")
        var before = (java.util.Map<String, Object>) changes.getFirst().get("before");
        @SuppressWarnings("unchecked")
        var after = (java.util.Map<String, Object>) changes.getFirst().get("after");
        assertThat(before).containsKey("subfamilyId").containsEntry("subfamilyId", null);
        assertThat(after).containsKey("subfamilyId").containsEntry("subfamilyId", null);
    }

    @Test
    void bulkMoveDerivesParentFromSubfamilyAndIgnoresClientFamily() {
        Family parent = new Family(storeId, "Bebidas", false);
        parent.assignCode("007");
        Family general = Family.general(storeId);
        Subfamily child = new Subfamily(parent.getId(), "Cafe");
        child.assignCode("007", "012");
        Product product = new Product(storeId, general.getId(), null, UUID.randomUUID(),
                ProductType.UNIT, DiscountType.NORMAL, "CAFE", null, null,
                BigDecimal.ZERO, true);
        when(familyRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(subfamilyRepository.findById(child.getId())).thenReturn(Optional.of(child));
        when(productRepository.findAllByStoreIdAndIdInForUpdate(any(), any())).thenReturn(List.of(product));
        when(productRepository.moveClassification(any(), any(), any(), any())).thenReturn(1);

        var result = service.moveProducts(new CatalogService.BulkMoveRequest(
                List.of(new CatalogService.MoveProductItem(product.getId(), 0L)),
                general.getId(), child.getId()));

        assertThat(result.familyId()).isEqualTo(parent.getId());
        assertThat(result.subfamilyId()).isEqualTo(child.getId());
        verify(productRepository).moveClassification(any(), any(), eq(parent.getId()), eq(child.getId()));
    }

    @Test
    void bulkMoveRejectsStaleVersionForeignProductAndDuplicateIdsBeforeUpdate() {
        Family family = new Family(storeId, "Bebidas", false);
        family.assignCode("007");
        Product product = new Product(storeId, family.getId(), null, UUID.randomUUID(),
                ProductType.UNIT, DiscountType.NORMAL, "CAFE", null, null,
                BigDecimal.ZERO, true);
        when(familyRepository.findById(family.getId())).thenReturn(Optional.of(family));
        when(productRepository.findAllByStoreIdAndIdInForUpdate(any(), any())).thenReturn(List.of(product));

        assertThatThrownBy(() -> service.moveProducts(new CatalogService.BulkMoveRequest(
                List.of(new CatalogService.MoveProductItem(product.getId(), 1L)), family.getId(), null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("version");
        assertThatThrownBy(() -> service.moveProducts(new CatalogService.BulkMoveRequest(
                List.of(new CatalogService.MoveProductItem(product.getId(), 0L),
                        new CatalogService.MoveProductItem(product.getId(), 0L)), family.getId(), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unicos");
        verify(productRepository, never()).moveClassification(any(), any(), any(), any());
    }

    @Test
    void bulkMoveReportsEveryStaleProductAndDoesNotAuditOrUpdate() {
        Family family = new Family(storeId, "Bebidas", false);
        family.assignCode("007");
        Product first = new Product(storeId, family.getId(), null, UUID.randomUUID(),
                ProductType.UNIT, DiscountType.NORMAL, "CAFE", null, null,
                BigDecimal.ZERO, true);
        Product second = new Product(storeId, family.getId(), null, UUID.randomUUID(),
                ProductType.UNIT, DiscountType.NORMAL, "TE", null, null,
                BigDecimal.ZERO, true);
        when(familyRepository.findById(family.getId())).thenReturn(Optional.of(family));
        when(productRepository.findAllByStoreIdAndIdInForUpdate(any(), any()))
                .thenReturn(List.of(first, second));

        assertThatThrownBy(() -> service.moveProducts(new CatalogService.BulkMoveRequest(
                List.of(new CatalogService.MoveProductItem(first.getId(), 1L),
                        new CatalogService.MoveProductItem(second.getId(), 2L)),
                family.getId(), null)))
                .isInstanceOf(ProductClassificationVersionConflictException.class)
                .satisfies(error -> assertThat(
                        ((ProductClassificationVersionConflictException) error).conflicts())
                        .hasSize(2));
        verify(productRepository, never()).moveClassification(any(), any(), any(), any());
        verify(auditService, never()).record(any(), any(), any());
    }

    @Test
    void bulkProductUpdateAcquiresStoreAndProductLocksOnlyOnce() {
        Product first = new Product(storeId, UUID.randomUUID(), null, UUID.randomUUID(),
                ProductType.UNIT, DiscountType.NORMAL, "CAFE", null, null,
                BigDecimal.ZERO, true);
        Product second = new Product(storeId, UUID.randomUUID(), null, UUID.randomUUID(),
                ProductType.UNIT, DiscountType.NORMAL, "TE", null, null,
                BigDecimal.ZERO, true);
        when(productRepository.findAllByStoreIdAndIdInForUpdate(eq(storeId), any()))
                .thenReturn(List.of(first, second));
        var request = new CatalogService.ProductRequest(
                UUID.randomUUID(), null, UUID.randomUUID(), ProductType.UNIT,
                DiscountType.NORMAL, PriceUseMode.NORMAL, "ACTUALIZADO", null, null,
                BigDecimal.ZERO, true, "A-1", null, null, BigDecimal.ONE,
                null, null, null, null, null, false, null, null);

        assertThatThrownBy(() -> service.updateProducts(List.of(
                new CatalogService.BulkProductUpdate(first.getId(), 1L, request),
                new CatalogService.BulkProductUpdate(second.getId(), 1L, request))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicto de version");

        verify(storeRepository).findByIdForUpdate(storeId);
        verify(productRepository).findAllByStoreIdAndIdInForUpdate(eq(storeId), any());
        verify(productRepository, never()).findAllByStoreIdAndIdIn(eq(storeId), any());
    }

    @Test
    void catalogMutationsEmitAuditableEventsWithCatalogIdentifiers() {
        Family general = Family.general(storeId);
        when(storeRepository.findByIdForUpdate(storeId)).thenReturn(Optional.of(store));
        when(familyRepository.existsByStoreIdAndNombreIgnoreCase(storeId, "BEBIDAS")).thenReturn(false);
        when(familyRepository.findByStoreIdOrderByFamilyCodeAscIdAsc(storeId)).thenReturn(List.of(general));
        when(familyRepository.findReservedFamilyCodes(storeId)).thenReturn(List.of());
        when(familyRepository.save(any(Family.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Family family = service.createFamily("Bebidas", "007");

        when(familyRepository.findById(family.getId())).thenReturn(Optional.of(family));
        when(subfamilyRepository.existsByFamilyIdAndNombreIgnoreCase(family.getId(), "CAFE"))
                .thenReturn(false);
        when(subfamilyRepository.findByFamilyIdOrderBySubfamilySuffixAscSubfamilyCodeAscIdAsc(family.getId()))
                .thenReturn(List.of());
        when(subfamilyRepository.findReservedSubfamilySuffixes(family.getId())).thenReturn(List.of());
        when(subfamilyRepository.save(any(Subfamily.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service.createSubfamily(family.getId(), "Cafe", "001");

        verify(auditService).record(eq("FAMILY_CREATED"), eq(AuditResult.EXITO), any());
        verify(auditService).record(eq("SUBFAMILY_CREATED"), eq(AuditResult.EXITO), any());
    }

    private static FamilyProductPageRepository.FamilyProductPageRow familyProductRow(
            UUID id,
            UUID familyId,
            UUID subfamilyId,
            String code,
            String barcode,
            String name,
            BigDecimal salePrice,
            boolean active,
            boolean nullSortValue,
            String sortValue) {
        return new FamilyProductPageRepository.FamilyProductPageRow(
                id, 4L, null, null, code, barcode, name, salePrice,
                familyId, subfamilyId, active, nullSortValue, sortValue);
    }
}
