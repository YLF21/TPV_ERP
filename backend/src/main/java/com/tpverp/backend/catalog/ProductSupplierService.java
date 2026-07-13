package com.tpverp.backend.catalog;

import com.tpverp.backend.document.ConfirmedPurchaseRecorder;
import com.tpverp.backend.document.ConfirmedPurchaseRecorder.PurchaseLine;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.DocumentType;
import com.tpverp.backend.party.Supplier;
import com.tpverp.backend.party.SupplierRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    @Transactional(readOnly = true)
    public List<SupplierOptionView> listSupplierOptions() {
        return suppliers.findByCompanyIdOrderByDocumentNumberAsc(
                        organization.currentCompany().getId())
                .stream()
                .map(SupplierOptionView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SupplierProductView> listSupplierProducts(UUID supplierId) {
        Supplier supplier = supplier(supplierId);
        var store = organization.currentStore();
        return links.findForSupplier(
                        supplier.getId(), organization.currentCompany().getId(), store.getId())
                .stream()
                .map(SupplierProductView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<StoreProductSupplierView> listForCurrentStore() {
        return links.findForStore(organization.currentStore().getId())
                .stream()
                .map(StoreProductSupplierView::from)
                .toList();
    }

    @Transactional
    public int linkProducts(UUID supplierId, Collection<UUID> productIds) {
        Supplier supplier = activeSupplier(supplierId);
        var uniqueIds = new LinkedHashSet<>(Objects.requireNonNull(productIds, "productIds"));
        if (uniqueIds.isEmpty()) {
            return 0;
        }
        UUID storeId = organization.currentStore().getId();
        List<Product> selectedProducts = products.findAllByStoreIdAndIdIn(storeId, uniqueIds);
        if (selectedProducts.size() != uniqueIds.size()) {
            throw new IllegalArgumentException("Producto no encontrado");
        }
        var linkedIds = new LinkedHashSet<>(
                links.findLinkedProductIds(supplier.getId(), uniqueIds));
        var missingLinks = selectedProducts.stream()
                .filter(product -> !linkedIds.contains(product.getId()))
                .map(product -> new ProductSupplier(product, supplier, null))
                .toList();
        links.saveAll(missingLinks);
        return missingLinks.size();
    }

    @Transactional
    public ProductSupplierView link(UUID productId, UUID supplierId, String reference) {
        return link(productId, supplierId, reference, null);
    }

    @Transactional
    public ProductSupplierView link(
            UUID productId,
            UUID supplierId,
            String reference,
            Boolean principal) {
        Product product = product(productId);
        Supplier supplier = activeSupplier(supplierId);
        if (links.findByProduct_IdAndSupplier_Id(productId, supplierId).isPresent()) {
            throw new IllegalStateException("El proveedor ya esta vinculado al producto");
        }
        ProductSupplier link = new ProductSupplier(product, supplier, reference);
        if (Boolean.TRUE.equals(principal)) {
            makePrincipal(product.getId(), supplier.getId(), link);
        }
        return ProductSupplierView.from(links.save(link));
    }

    @Transactional
    public ProductSupplierView updateReference(
            UUID productId, UUID supplierId, String reference) {
        return update(productId, supplierId, reference, null);
    }

    @Transactional
    public ProductSupplierView update(
            UUID productId,
            UUID supplierId,
            String reference,
            Boolean principal) {
        ProductSupplier link = link(productId, supplierId);
        link.changeReference(reference);
        if (principal != null) {
            if (principal) {
                makePrincipal(productId, supplierId, link);
            } else {
                link.clearPrincipal();
            }
        }
        return ProductSupplierView.from(link);
    }

    @Transactional
    public void unlink(UUID productId, UUID supplierId) {
        links.delete(link(productId, supplierId));
    }

    @Override
    @Transactional
    public void record(UUID supplierId, Instant entryAt, Collection<PurchaseLine> purchaseLines) {
        // La ultima linea repetida del documento prevalece para cada producto.
        Supplier supplier = activeSupplier(supplierId);
        Instant confirmedAt = Objects.requireNonNull(entryAt, "entryAt");
        Map<UUID, PurchaseLine> lastLineByProduct = new java.util.LinkedHashMap<>();
        Objects.requireNonNull(purchaseLines, "purchaseLines").forEach(line ->
                lastLineByProduct.put(Objects.requireNonNull(line.productId(), "productId"), line));
        var uniqueIds = new LinkedHashSet<>(lastLineByProduct.keySet());
        List<Product> uniqueProducts = products.findAllByStoreIdAndIdIn(
                organization.currentStore().getId(), uniqueIds);
        if (uniqueProducts.size() != uniqueIds.size()) {
            throw new IllegalArgumentException("Producto no encontrado");
        }
        for (Product product : uniqueProducts) {
            PurchaseLine line = lastLineByProduct.get(product.getId());
            links.lockProduct(product.getId());
            boolean makePrincipal = !links.existsByProduct_IdAndPrincipalTrue(product.getId());
            Instant latestEntryAt = links.findLatestEntryAtForProduct(product.getId());
            boolean makeLastSupplier = latestEntryAt == null || !confirmedAt.isBefore(latestEntryAt);
            if (makeLastSupplier) {
                links.clearLastSupplier(product.getId(), supplier.getId());
            }
            links.upsertPurchase(
                    UUID.randomUUID(), product.getId(), supplier.getId(),
                    normalizeReference(line.supplierReference()),
                    makePrincipal,
                    makeLastSupplier,
                    line.grossPurchasePrice(),
                    line.purchaseDiscount(),
                    confirmedAt);
        }
    }

    private Product product(UUID productId) {
        UUID storeId = organization.currentStore().getId();
        return products.findById(productId)
                .filter(product -> product.getStoreId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("Producto no encontrado"));
    }

    private Supplier activeSupplier(UUID supplierId) {
        Supplier supplier = supplier(supplierId);
        if (!supplier.isActive()) {
            throw new IllegalStateException("El proveedor esta inactivo");
        }
        return supplier;
    }

    private Supplier supplier(UUID supplierId) {
        return suppliers.findByIdAndCompanyId(
                        supplierId, organization.currentCompany().getId())
                .orElseThrow(() -> new IllegalArgumentException("Proveedor no encontrado"));
    }

    private ProductSupplier link(UUID productId, UUID supplierId) {
        product(productId);
        return links.findByProduct_IdAndSupplier_Id(productId, supplierId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Proveedor no vinculado al producto"));
    }

    private void makePrincipal(UUID productId, UUID supplierId, ProductSupplier link) {
        links.lockProduct(productId);
        links.clearPrincipal(productId, supplierId);
        link.makePrincipal();
    }

    public record ProductSupplierView(
            UUID supplierId,
            String legalName,
            DocumentType documentType,
            String documentNumber,
            boolean active,
            String supplierReference,
            boolean principal,
            boolean lastSupplier,
            java.math.BigDecimal grossPurchasePrice,
            java.math.BigDecimal purchaseDiscount,
            java.math.BigDecimal netPurchasePrice,
            Instant lastEntryAt) {

        static ProductSupplierView from(ProductSupplier link) {
            Supplier supplier = link.getSupplier();
            return new ProductSupplierView(
                    supplier.getId(),
                    supplier.getLegalName(),
                    supplier.getDocumentType(),
                    supplier.getDocumentNumber(),
                    supplier.isActive(),
                    link.getSupplierReference(),
                    link.isPrincipal(),
                    link.isLastSupplier(),
                    link.getGrossPurchasePrice(),
                    link.getPurchaseDiscount(),
                    link.getNetPurchasePrice(),
                    link.getLastEntryAt());
        }
    }

    public record SupplierOptionView(
            UUID id,
            String supplierCode,
            String legalName,
            String tradeName,
            DocumentType documentType,
            String documentNumber,
            boolean active) {

        static SupplierOptionView from(Supplier supplier) {
            return new SupplierOptionView(
                    supplier.getId(),
                    supplier.getSupplierId(),
                    supplier.getLegalName(),
                    supplier.getTradeName(),
                    supplier.getDocumentType(),
                    supplier.getDocumentNumber(),
                    supplier.isActive());
        }
    }

    public record SupplierProductView(
            UUID productId,
            String supplierReference,
            boolean principal,
            boolean lastSupplier,
            java.math.BigDecimal grossPurchasePrice,
            java.math.BigDecimal purchaseDiscount,
            java.math.BigDecimal netPurchasePrice,
            Instant lastEntryAt) {

        static SupplierProductView from(ProductSupplier link) {
            return new SupplierProductView(
                    link.getProductId(),
                    link.getSupplierReference(),
                    link.isPrincipal(),
                    link.isLastSupplier(),
                    link.getGrossPurchasePrice(),
                    link.getPurchaseDiscount(),
                    link.getNetPurchasePrice(),
                    link.getLastEntryAt());
        }
    }

    public record StoreProductSupplierView(
            UUID productId,
            UUID supplierId,
            String supplierCode,
            String legalName,
            String tradeName,
            String documentNumber,
            boolean active,
            String supplierReference,
            boolean principal,
            boolean lastSupplier,
            java.math.BigDecimal grossPurchasePrice,
            java.math.BigDecimal purchaseDiscount,
            java.math.BigDecimal netPurchasePrice,
            Instant lastEntryAt) {

        static StoreProductSupplierView from(ProductSupplier link) {
            Supplier supplier = link.getSupplier();
            return new StoreProductSupplierView(
                    link.getProductId(),
                    supplier.getId(),
                    supplier.getSupplierId(),
                    supplier.getLegalName(),
                    supplier.getTradeName(),
                    supplier.getDocumentNumber(),
                    supplier.isActive(),
                    link.getSupplierReference(),
                    link.isPrincipal(),
                    link.isLastSupplier(),
                    link.getGrossPurchasePrice(),
                    link.getPurchaseDiscount(),
                    link.getNetPurchasePrice(),
                    link.getLastEntryAt());
        }
    }

    private static String normalizeReference(String reference) {
        return reference == null || reference.isBlank()
                ? null
                : reference.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
