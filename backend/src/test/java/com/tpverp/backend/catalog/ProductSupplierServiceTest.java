package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.document.ConfirmedPurchaseRecorder.PurchaseLine;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.DocumentType;
import com.tpverp.backend.party.Supplier;
import com.tpverp.backend.party.SupplierRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductSupplierServiceTest {

    @Mock private ProductRepository products;
    @Mock private SupplierRepository suppliers;
    @Mock private ProductSupplierRepository links;
    @Mock private CurrentOrganization organization;

    private ProductSupplierService service;
    private Company company;
    private Store store;
    private Product product;
    private Supplier supplier;

    @BeforeEach
    void setUp() {
        company = new Company("B00000000", "Company", address());
        store = new Store(company, "Store", address(), "hash",
                "Atlantic/Canary", "EUR", "es-ES");
        product = product(store.getId());
        supplier = supplier("B00000002", "Proveedor");
        service = new ProductSupplierService(products, suppliers, links, organization);
    }

    @Test
    void linksOnlyActiveSuppliersFromTheCurrentCompany() {
        currentStoreAndCompany();
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(suppliers.findByIdAndCompanyId(supplier.getId(), company.getId()))
                .thenReturn(Optional.of(supplier));
        when(links.findByProduct_IdAndSupplier_Id(product.getId(), supplier.getId()))
                .thenReturn(Optional.empty());
        when(links.save(any())).thenAnswer(call -> call.getArgument(0));

        var result = service.link(product.getId(), supplier.getId(), " ref ");

        assertThat(result.supplierReference()).isEqualTo("REF");
        assertThat(result.supplierId()).isEqualTo(supplier.getId());
    }

    @Test
    void manualLinkCanSelectThePrincipalSupplier() {
        currentStoreAndCompany();
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(suppliers.findByIdAndCompanyId(supplier.getId(), company.getId()))
                .thenReturn(Optional.of(supplier));
        when(links.findByProduct_IdAndSupplier_Id(product.getId(), supplier.getId()))
                .thenReturn(Optional.empty());
        when(links.save(any())).thenAnswer(call -> call.getArgument(0));

        var result = service.link(product.getId(), supplier.getId(), null, true);

        assertThat(result.principal()).isTrue();
        verify(links).lockProduct(product.getId());
        verify(links).clearPrincipal(product.getId(), supplier.getId());
    }

    @Test
    void setPrincipalRequiresAnExistingLinkAndPreservesItsReference() {
        var existing = new ProductSupplier(product, supplier, "REF-EXISTENTE");
        currentStore();
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(links.findByProduct_IdAndSupplier_Id(product.getId(), supplier.getId()))
                .thenReturn(Optional.of(existing));

        var result = service.setPrincipal(product.getId(), supplier.getId());

        assertThat(result.principal()).isTrue();
        assertThat(result.supplierReference()).isEqualTo("REF-EXISTENTE");
        var ordered = inOrder(links);
        ordered.verify(links).lockProduct(product.getId());
        ordered.verify(links).clearPrincipal(product.getId(), supplier.getId());
    }

    @Test
    void setPrincipalRejectsAMissingLinkBeforeLockingTheProduct() {
        currentStore();
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(links.findByProduct_IdAndSupplier_Id(product.getId(), supplier.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setPrincipal(product.getId(), supplier.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no vinculado");

        verify(links, never()).lockProduct(any(UUID.class));
        verify(links, never()).clearPrincipal(any(UUID.class), any(UUID.class));
    }

    @Test
    void clearPrincipalLocksTheProductAndDoesNotLoadOrChangeAnyLink() {
        currentStore();
        when(products.findById(product.getId())).thenReturn(Optional.of(product));

        service.clearPrincipal(product.getId());

        var ordered = inOrder(links);
        ordered.verify(links).lockProduct(product.getId());
        ordered.verify(links).clearPrincipal(product.getId());
        verify(links, never()).findByProduct_IdAndSupplier_Id(any(), any());
        verify(links, never()).save(any());
    }

    @Test
    void rejectsInactiveSupplier() {
        supplier.deactivate();
        currentStoreAndCompany();
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(suppliers.findByIdAndCompanyId(supplier.getId(), company.getId()))
                .thenReturn(Optional.of(supplier));

        assertThatThrownBy(() -> service.link(product.getId(), supplier.getId(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inactivo");

        verify(links, never()).save(any());
    }

    @Test
    void rejectsDuplicateLink() {
        var existing = new ProductSupplier(product, supplier, null);
        currentStoreAndCompany();
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(suppliers.findByIdAndCompanyId(supplier.getId(), company.getId()))
                .thenReturn(Optional.of(supplier));
        when(links.findByProduct_IdAndSupplier_Id(product.getId(), supplier.getId()))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.link(product.getId(), supplier.getId(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vinculado");
    }

    @Test
    void updatesOptionalReferenceOnExistingLink() {
        var existing = new ProductSupplier(product, supplier, "OLD");
        currentStore();
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(links.findByProduct_IdAndSupplier_Id(product.getId(), supplier.getId()))
                .thenReturn(Optional.of(existing));

        var result = service.updateReference(product.getId(), supplier.getId(), " new ");

        assertThat(result.supplierReference()).isEqualTo("NEW");
    }

    @Test
    void unlinksExistingRelation() {
        var existing = new ProductSupplier(product, supplier, null);
        currentStore();
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(links.findByProduct_IdAndSupplier_Id(product.getId(), supplier.getId()))
                .thenReturn(Optional.of(existing));

        service.unlink(product.getId(), supplier.getId());

        verify(links).delete(existing);
    }

    @Test
    void rejectsProductFromAnotherStore() {
        Product foreignProduct = product(UUID.randomUUID());
        when(organization.currentStore()).thenReturn(store);
        when(products.findById(foreignProduct.getId())).thenReturn(Optional.of(foreignProduct));

        assertThatThrownBy(() ->
                service.link(foreignProduct.getId(), supplier.getId(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Producto no encontrado");

        verify(suppliers, never()).findByIdAndCompanyId(any(), any());
    }

    @Test
    void rejectsSupplierFromAnotherCompany() {
        currentStoreAndCompany();
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(suppliers.findByIdAndCompanyId(supplier.getId(), company.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.link(product.getId(), supplier.getId(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Proveedor no encontrado");
    }

    @Test
    void listUsesRepositoryOrderByDocumentNumber() {
        Supplier first = supplier("A00000001", "Primero");
        Supplier second = supplier("B00000001", "Segundo");
        currentStore();
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(links.findForProduct(product.getId(), store.getId())).thenReturn(List.of(
                new ProductSupplier(product, first, null),
                new ProductSupplier(product, second, null)));

        assertThat(service.list(product.getId()))
                .extracting(ProductSupplierService.ProductSupplierView::documentNumber)
                .containsExactly("A00000001", "B00000001");
    }

    @Test
    void listsAllProductSupplierDataForTheCurrentStore() {
        Supplier linkedSupplier = new Supplier(
                company,
                "Proveedor Fiscal",
                "Proveedor Tienda",
                DocumentType.CIF,
                "B00000042",
                null,
                null,
                null,
                null);
        linkedSupplier.assignCode("00000042");
        ProductSupplier link = new ProductSupplier(product, linkedSupplier, " ref-42 ");
        link.makePrincipal();
        Instant lastEntryAt = Instant.parse("2026-07-11T10:15:30Z");
        link.registerEntry(
                lastEntryAt,
                new BigDecimal("100.00"),
                new BigDecimal("12.50"));
        currentStore();
        when(links.findForStore(store.getId())).thenReturn(List.of(link));

        assertThat(service.listForCurrentStore())
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.productId()).isEqualTo(product.getId());
                    assertThat(view.supplierId()).isEqualTo(linkedSupplier.getId());
                    assertThat(view.supplierCode()).isEqualTo("00000042");
                    assertThat(view.legalName()).isEqualTo("Proveedor Fiscal");
                    assertThat(view.tradeName()).isEqualTo("Proveedor Tienda");
                    assertThat(view.documentNumber()).isEqualTo("B00000042");
                    assertThat(view.active()).isTrue();
                    assertThat(view.supplierReference()).isEqualTo("REF-42");
                    assertThat(view.principal()).isTrue();
                    assertThat(view.lastSupplier()).isTrue();
                    assertThat(view.grossPurchasePrice()).isEqualByComparingTo("100.00");
                    assertThat(view.purchaseDiscount()).isEqualByComparingTo("12.50");
                    assertThat(view.netPurchasePrice()).isEqualByComparingTo("87.50");
                    assertThat(view.lastEntryAt()).isEqualTo(lastEntryAt);
                });

        verify(links).findForStore(store.getId());
    }

    @Test
    void listsEveryProductLinkedToSupplierRegardlessOfLastSupplierFlag() {
        Product secondProduct = product(store.getId());
        ProductSupplier previousLink = new ProductSupplier(product, supplier, "REF-OLD");
        ProductSupplier latestLink = new ProductSupplier(secondProduct, supplier, "REF-NEW");
        latestLink.registerEntry(
                Instant.parse("2026-07-11T10:00:00Z"),
                new BigDecimal("12.00"),
                new BigDecimal("10.00"));
        currentStoreAndCompany();
        when(suppliers.findByIdAndCompanyId(supplier.getId(), company.getId()))
                .thenReturn(Optional.of(supplier));
        when(links.findForSupplier(supplier.getId(), company.getId(), store.getId()))
                .thenReturn(List.of(previousLink, latestLink));

        assertThat(service.listSupplierProducts(supplier.getId()))
                .extracting(ProductSupplierService.SupplierProductView::productId)
                .containsExactly(product.getId(), secondProduct.getId());
        assertThat(service.listSupplierProducts(supplier.getId()))
                .extracting(ProductSupplierService.SupplierProductView::lastSupplier)
                .containsExactly(false, true);
    }

    @Test
    void linksOnlyMissingProductsWhenAssigningSupplierInBulk() {
        Product secondProduct = product(store.getId());
        currentStoreAndCompany();
        when(suppliers.findByIdAndCompanyId(supplier.getId(), company.getId()))
                .thenReturn(Optional.of(supplier));
        when(products.findAllByStoreIdAndIdIn(eq(store.getId()), any()))
                .thenReturn(List.of(product, secondProduct));
        when(links.findLinkedProductIds(eq(supplier.getId()), any()))
                .thenReturn(List.of(product.getId()));

        int created = service.linkProducts(
                supplier.getId(), List.of(product.getId(), secondProduct.getId(), product.getId()));

        assertThat(created).isEqualTo(1);
        verify(links).saveAll(org.mockito.ArgumentMatchers.argThat(values -> {
            var saved = (List<ProductSupplier>) values;
            return saved.size() == 1 && saved.getFirst().getProductId().equals(secondProduct.getId());
        }));
    }

    @Test
    void confirmedPurchaseCreatesMissingLinkWithNullReference() {
        currentStoreAndCompany();
        when(suppliers.findByIdAndCompanyId(supplier.getId(), company.getId()))
                .thenReturn(Optional.of(supplier));
        when(products.findAllByStoreIdAndIdIn(
                eq(store.getId()), any())).thenReturn(List.of(product));

        Instant entryAt = Instant.parse("2026-06-09T10:15:30Z");
        service.record(supplier.getId(), entryAt, List.of(
                line(product, null, "2.50", "5.00")));

        verify(links).clearLastSupplier(product.getId(), supplier.getId());
        verify(links).upsertPurchase(
                any(), org.mockito.ArgumentMatchers.eq(product.getId()),
                org.mockito.ArgumentMatchers.eq(supplier.getId()),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("2.50")),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("5.00")),
                org.mockito.ArgumentMatchers.eq(entryAt));
        verify(links, never()).findByProduct_IdAndSupplier_Id(any(), any());
        verify(links, never()).save(any());
    }

    @Test
    void confirmedPurchaseCanStoreSupplierReferences() {
        currentStoreAndCompany();
        when(suppliers.findByIdAndCompanyId(supplier.getId(), company.getId()))
                .thenReturn(Optional.of(supplier));
        when(products.findAllByStoreIdAndIdIn(
                eq(store.getId()), any())).thenReturn(List.of(product));

        Instant entryAt = Instant.parse("2026-06-09T10:15:30Z");
        service.record(supplier.getId(), entryAt, List.of(
                line(product, " ref-1 ", "2.50", "5.00")));

        verify(links).upsertPurchase(
                any(), org.mockito.ArgumentMatchers.eq(product.getId()),
                org.mockito.ArgumentMatchers.eq(supplier.getId()),
                org.mockito.ArgumentMatchers.eq("REF-1"),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("2.50")),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("5.00")),
                org.mockito.ArgumentMatchers.eq(entryAt));
    }

    @Test
    void confirmedPurchaseNeverReadsOrChangesPrincipalState() {
        currentStoreAndCompany();
        when(suppliers.findByIdAndCompanyId(supplier.getId(), company.getId()))
                .thenReturn(Optional.of(supplier));
        when(products.findAllByStoreIdAndIdIn(eq(store.getId()), any()))
                .thenReturn(List.of(product));

        Instant entryAt = Instant.parse("2026-06-09T10:15:30Z");
        service.record(supplier.getId(), entryAt, List.of(
                line(product, null, "2.50", "5.00")));

        verify(links).upsertPurchase(
                any(), org.mockito.ArgumentMatchers.eq(product.getId()),
                org.mockito.ArgumentMatchers.eq(supplier.getId()),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("2.50")),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("5.00")),
                org.mockito.ArgumentMatchers.eq(entryAt));
        verify(links, never()).existsByProduct_IdAndPrincipalTrue(any(UUID.class));
        verify(links, never()).clearPrincipal(any(UUID.class), any(UUID.class));
        verify(links, never()).clearPrincipal(any(UUID.class));
    }


    @Test
    void confirmedPurchaseDelegatesNonDecreasingDateToAtomicUpsert() {
        currentStoreAndCompany();
        when(suppliers.findByIdAndCompanyId(supplier.getId(), company.getId()))
                .thenReturn(Optional.of(supplier));
        when(products.findAllByStoreIdAndIdIn(
                eq(store.getId()), any())).thenReturn(List.of(product));

        Instant entryAt = Instant.parse("2026-05-01T08:00:00Z");
        service.record(supplier.getId(), entryAt, List.of(
                line(product, null, "2.50", "0.00")));

        verify(links).upsertPurchase(
                any(), org.mockito.ArgumentMatchers.eq(product.getId()),
                org.mockito.ArgumentMatchers.eq(supplier.getId()),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("2.50")),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("0.00")),
                org.mockito.ArgumentMatchers.eq(entryAt));
    }

    @Test
    void olderPurchaseDoesNotDisplaceTheLastSupplier() {
        currentStoreAndCompany();
        when(suppliers.findByIdAndCompanyId(supplier.getId(), company.getId()))
                .thenReturn(Optional.of(supplier));
        when(products.findAllByStoreIdAndIdIn(eq(store.getId()), any()))
                .thenReturn(List.of(product));
        when(links.findLatestEntryAtForProduct(product.getId()))
                .thenReturn(Instant.parse("2026-06-09T10:15:30Z"));
        Instant oldEntry = Instant.parse("2026-05-01T08:00:00Z");

        service.record(supplier.getId(), oldEntry, List.of(
                line(product, null, "2.50", "0.00")));

        verify(links, never()).clearLastSupplier(product.getId(), supplier.getId());
        verify(links).upsertPurchase(
                any(), eq(product.getId()), eq(supplier.getId()),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(false),
                eq(new BigDecimal("2.50")), eq(new BigDecimal("0.00")), eq(oldEntry));
    }

    @Test
    void confirmedPurchaseProcessesDuplicateProductIdsOnce() {
        Product secondProduct = product(store.getId());
        currentStoreAndCompany();
        when(suppliers.findByIdAndCompanyId(supplier.getId(), company.getId()))
                .thenReturn(Optional.of(supplier));
        when(products.findAllByStoreIdAndIdIn(
                eq(store.getId()), any())).thenReturn(List.of(product, secondProduct));

        Instant entryAt = Instant.parse("2026-06-09T10:15:30Z");
        service.record(supplier.getId(), entryAt, List.of(
                line(product, null, "2.00", "0.00"),
                line(secondProduct, null, "3.00", "5.00"),
                line(product, null, "2.50", "10.00")));

        verify(products, times(1)).findAllByStoreIdAndIdIn(
                eq(store.getId()), any());
        verify(products, never()).findById(any());
        verify(links).upsertPurchase(
                any(), org.mockito.ArgumentMatchers.eq(product.getId()),
                org.mockito.ArgumentMatchers.eq(supplier.getId()),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("2.50")),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("10.00")),
                org.mockito.ArgumentMatchers.eq(entryAt));
        verify(links).upsertPurchase(
                any(), org.mockito.ArgumentMatchers.eq(secondProduct.getId()),
                org.mockito.ArgumentMatchers.eq(supplier.getId()),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("3.00")),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("5.00")),
                org.mockito.ArgumentMatchers.eq(entryAt));
    }

    @Test
    void confirmedPurchaseRejectsInactiveSupplierBeforeWritingRelations() {
        supplier.deactivate();
        when(organization.currentCompany()).thenReturn(company);
        when(suppliers.findByIdAndCompanyId(supplier.getId(), company.getId()))
                .thenReturn(Optional.of(supplier));

        assertThatThrownBy(() -> service.record(
                supplier.getId(),
                Instant.parse("2026-06-09T10:15:30Z"),
                List.of(line(product, null, "2.50", "0.00"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inactivo");

        verify(products, never()).findById(any());
        verify(links, never()).upsertPurchase(
                any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any());
        verify(links, never()).save(any());
    }

    @Test
    void confirmedPurchaseValidatesEveryProductBeforeWriting() {
        Product missing = product(store.getId());
        currentStoreAndCompany();
        when(suppliers.findByIdAndCompanyId(supplier.getId(), company.getId()))
                .thenReturn(Optional.of(supplier));
        when(products.findAllByStoreIdAndIdIn(
                eq(store.getId()), any())).thenReturn(List.of(product));

        assertThatThrownBy(() -> service.record(
                supplier.getId(),
                Instant.parse("2026-06-09T10:15:30Z"),
                List.of(
                        line(product, null, "2.50", "0.00"),
                        line(missing, null, "3.00", "0.00"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Producto no encontrado");

        verify(links, never()).upsertPurchase(
                any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any());
    }

    private void currentStoreAndCompany() {
        currentStore();
        when(organization.currentCompany()).thenReturn(company);
    }

    private void currentStore() {
        when(organization.currentStore()).thenReturn(store);
    }

    private Product product(UUID storeId) {
        return new Product(storeId, UUID.randomUUID(), null, UUID.randomUUID(),
                "Producto", null, BigDecimal.ZERO, true);
    }

    private PurchaseLine line(Product product, String reference, String grossPrice, String discount) {
        return new PurchaseLine(
                product.getId(), reference, new BigDecimal(grossPrice), new BigDecimal(discount));
    }

    private Supplier supplier(String documentNumber, String legalName) {
        return new Supplier(company, legalName, null, DocumentType.CIF, documentNumber,
                null, null, null, null);
    }

    private static Map<String, String> address() {
        return Map.of(
                "linea1", "Calle Uno",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
    }
}
