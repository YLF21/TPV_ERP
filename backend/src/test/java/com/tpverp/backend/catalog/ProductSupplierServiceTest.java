package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.DocumentType;
import com.tpverp.backend.party.Supplier;
import com.tpverp.backend.party.SupplierRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
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
    void confirmedPurchaseCreatesMissingLinkWithNullReference() {
        currentStoreAndCompany();
        when(suppliers.findByIdAndCompanyId(supplier.getId(), company.getId()))
                .thenReturn(Optional.of(supplier));
        when(products.findAllByStoreIdAndIdIn(
                eq(store.getId()), any())).thenReturn(List.of(product));

        service.record(
                supplier.getId(),
                LocalDate.of(2026, 6, 9),
                List.of(product.getId()));

        verify(links).upsertPurchase(
                any(), org.mockito.ArgumentMatchers.eq(product.getId()),
                org.mockito.ArgumentMatchers.eq(supplier.getId()),
                org.mockito.ArgumentMatchers.eq(LocalDate.of(2026, 6, 9)));
        verify(links, never()).findByProduct_IdAndSupplier_Id(any(), any());
        verify(links, never()).save(any());
    }

    @Test
    void confirmedPurchaseDelegatesNonDecreasingDateToAtomicUpsert() {
        currentStoreAndCompany();
        when(suppliers.findByIdAndCompanyId(supplier.getId(), company.getId()))
                .thenReturn(Optional.of(supplier));
        when(products.findAllByStoreIdAndIdIn(
                eq(store.getId()), any())).thenReturn(List.of(product));

        service.record(
                supplier.getId(),
                LocalDate.of(2026, 5, 1),
                List.of(product.getId()));

        verify(links).upsertPurchase(
                any(), org.mockito.ArgumentMatchers.eq(product.getId()),
                org.mockito.ArgumentMatchers.eq(supplier.getId()),
                org.mockito.ArgumentMatchers.eq(LocalDate.of(2026, 5, 1)));
    }

    @Test
    void confirmedPurchaseProcessesDuplicateProductIdsOnce() {
        Product secondProduct = product(store.getId());
        currentStoreAndCompany();
        when(suppliers.findByIdAndCompanyId(supplier.getId(), company.getId()))
                .thenReturn(Optional.of(supplier));
        when(products.findAllByStoreIdAndIdIn(
                eq(store.getId()), any())).thenReturn(List.of(product, secondProduct));

        service.record(
                supplier.getId(),
                LocalDate.of(2026, 6, 9),
                List.of(product.getId(), secondProduct.getId(), product.getId()));

        verify(products, times(1)).findAllByStoreIdAndIdIn(
                eq(store.getId()), any());
        verify(products, never()).findById(any());
        verify(links).upsertPurchase(
                any(), org.mockito.ArgumentMatchers.eq(product.getId()),
                org.mockito.ArgumentMatchers.eq(supplier.getId()),
                org.mockito.ArgumentMatchers.eq(LocalDate.of(2026, 6, 9)));
        verify(links).upsertPurchase(
                any(), org.mockito.ArgumentMatchers.eq(secondProduct.getId()),
                org.mockito.ArgumentMatchers.eq(supplier.getId()),
                org.mockito.ArgumentMatchers.eq(LocalDate.of(2026, 6, 9)));
    }

    @Test
    void confirmedPurchaseRejectsInactiveSupplierBeforeWritingRelations() {
        supplier.deactivate();
        when(organization.currentCompany()).thenReturn(company);
        when(suppliers.findByIdAndCompanyId(supplier.getId(), company.getId()))
                .thenReturn(Optional.of(supplier));

        assertThatThrownBy(() -> service.record(
                supplier.getId(),
                LocalDate.of(2026, 6, 9),
                List.of(product.getId())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inactivo");

        verify(products, never()).findById(any());
        verify(links, never()).upsertPurchase(any(), any(), any(), any());
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
                LocalDate.of(2026, 6, 9),
                List.of(product.getId(), missing.getId())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Producto no encontrado");

        verify(links, never()).upsertPurchase(any(), any(), any(), any());
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
