package com.tpverp.backend.catalog;

import com.tpverp.backend.document.ConfirmedPurchaseRecorder;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.DocumentType;
import com.tpverp.backend.party.Supplier;
import com.tpverp.backend.party.SupplierRepository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductSupplierService implements ConfirmedPurchaseRecorder {

    private final ProductRepository products;
    private final SupplierRepository suppliers;
    private final ProductSupplierRepository links;
    private final CurrentOrganization organization;

    public ProductSupplierService(
            ProductRepository products,
            SupplierRepository suppliers,
            ProductSupplierRepository links,
            CurrentOrganization organization) {
        this.products = products;
        this.suppliers = suppliers;
        this.links = links;
        this.organization = organization;
    }

    @Transactional(readOnly = true)
    public List<ProductSupplierView> list(UUID productId) {
        Product product = product(productId);
        return links.findForProduct(product.getId(), product.getStoreId())
                .stream()
                .map(ProductSupplierView::from)
                .toList();
    }

    @Transactional
    public ProductSupplierView link(UUID productId, UUID supplierId, String reference) {
        Product product = product(productId);
        Supplier supplier = activeSupplier(supplierId);
        if (links.findByProduct_IdAndSupplier_Id(productId, supplierId).isPresent()) {
            throw new IllegalArgumentException("El proveedor ya esta vinculado al producto");
        }
        return ProductSupplierView.from(
                links.save(new ProductSupplier(product, supplier, reference)));
    }

    @Transactional
    public ProductSupplierView updateReference(
            UUID productId, UUID supplierId, String reference) {
        ProductSupplier link = link(productId, supplierId);
        link.changeReference(reference);
        return ProductSupplierView.from(link);
    }

    @Transactional
    public void unlink(UUID productId, UUID supplierId) {
        links.delete(link(productId, supplierId));
    }

    @Override
    @Transactional
    public void record(UUID supplierId, LocalDate date, Collection<UUID> productIds) {
        // Valida todo antes de escribir y delega la concurrencia al UPSERT atomico.
        Supplier supplier = activeSupplier(supplierId);
        LocalDate entryDate = Objects.requireNonNull(date, "fechaEntrada");
        List<Product> uniqueProducts = new LinkedHashSet<>(
                Objects.requireNonNull(productIds, "productos"))
                .stream()
                .map(this::product)
                .toList();
        for (Product product : uniqueProducts) {
            links.upsertPurchase(
                    UUID.randomUUID(), product.getId(), supplier.getId(), entryDate);
        }
    }

    private Product product(UUID productId) {
        UUID storeId = organization.currentStore().getId();
        return products.findById(productId)
                .filter(product -> product.getStoreId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("Producto no encontrado"));
    }

    private Supplier activeSupplier(UUID supplierId) {
        Supplier supplier = suppliers.findByIdAndCompanyId(
                        supplierId, organization.currentCompany().getId())
                .orElseThrow(() -> new IllegalArgumentException("Proveedor no encontrado"));
        if (!supplier.isActive()) {
            throw new IllegalStateException("El proveedor esta inactivo");
        }
        return supplier;
    }

    private ProductSupplier link(UUID productId, UUID supplierId) {
        product(productId);
        return links.findByProduct_IdAndSupplier_Id(productId, supplierId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Proveedor no vinculado al producto"));
    }

    public record ProductSupplierView(
            UUID supplierId,
            String legalName,
            DocumentType documentType,
            String documentNumber,
            boolean active,
            String supplierReference,
            LocalDate lastEntryDate) {

        static ProductSupplierView from(ProductSupplier link) {
            Supplier supplier = link.getSupplier();
            return new ProductSupplierView(
                    supplier.getId(),
                    supplier.getLegalName(),
                    supplier.getDocumentType(),
                    supplier.getDocumentNumber(),
                    supplier.isActive(),
                    link.getSupplierReference(),
                    link.getLastEntryDate());
        }
    }
}
