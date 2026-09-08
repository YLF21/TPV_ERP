package com.tpverp.backend.excel;

import com.tpverp.backend.catalog.CatalogService;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.ProductClassificationVersionConflictException;
import com.tpverp.backend.catalog.ProductImportConflictException;
import com.tpverp.backend.organization.CurrentOrganization;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transaction boundary for the complete product batch. */
@Service
public class ProductExcelImportApplyWriter {
    private final CatalogService catalog;
    private final ProductRepository products;
    private final CurrentOrganization organization;

    public ProductExcelImportApplyWriter(CatalogService catalog, ProductRepository products,
            CurrentOrganization organization) {
        this.catalog = catalog;
        this.products = products;
        this.organization = organization;
    }

    @Transactional
    public List<AppliedProduct> write(List<ProductExcelImportApplyService.WriteItem> items) {
        if (items == null || items.isEmpty()) return List.of();
        UUID storeId = organization.currentStore().getId();
        // Keep the same store -> product lock order used by CatalogService.
        catalog.lockStoreForCatalogMutation(storeId);
        List<UUID> existingIds = items.stream().map(ProductExcelImportApplyService.WriteItem::existing)
                .filter(java.util.Objects::nonNull).map(ProductExcelImportApplyService.ExpectedProduct::productId).toList();
        Map<UUID, Product> locked = new HashMap<>();
        if (!existingIds.isEmpty()) {
            for (Product product : products.findAllByStoreIdAndIdInForUpdate(storeId, existingIds)) locked.put(product.getId(), product);
            if (locked.size() != new java.util.HashSet<>(existingIds).size()) throw new IllegalStateException("Producto no encontrado");
            for (ProductExcelImportApplyService.WriteItem item : items) {
                if (item.existing() != null) {
                    Product current = locked.get(item.existing().productId());
                    if (current.getVersion() != item.existing().version()) {
                        throw new StaleVersionException(current.getId(), item.existing().version(), current.getVersion());
                    }
                }
            }
        }
        List<AppliedProduct> result = new ArrayList<>(items.size());
        List<ProductExcelImportApplyService.WriteItem> existingUpdates = items.stream()
                .filter(item -> item.existing() != null && item.request() != null).toList();
        Map<UUID, Product> updated = new HashMap<>();
        if (!existingUpdates.isEmpty()) {
            List<CatalogService.BulkProductUpdate> updates = existingUpdates.stream()
                    .map(item -> new CatalogService.BulkProductUpdate(item.existing().productId(),
                            item.existing().version(), item.request())).toList();
            try {
                for (Product product : catalog.updateProducts(updates)) updated.put(product.getId(), product);
            } catch (ProductClassificationVersionConflictException | ProductImportConflictException exception) {
                throw new ConcurrentImportConflictException(exception.getMessage(), exception);
            }
        }
        List<ProductExcelImportApplyService.WriteItem> missing = items.stream()
                .filter(item -> item.existing() == null && item.request() != null).toList();
        List<Product> created;
        try {
            created = missing.isEmpty() ? List.of()
                    : catalog.createProductsFromImport(missing.stream()
                            .map(ProductExcelImportApplyService.WriteItem::request).toList());
        } catch (ProductImportConflictException exception) {
            throw new ConcurrentImportConflictException(exception.getMessage(), exception);
        }
        Map<ProductExcelImportApplyService.WriteItem, Product> createdByItem = new HashMap<>();
        for (int index = 0; index < missing.size(); index++) createdByItem.put(missing.get(index), created.get(index));
        for (ProductExcelImportApplyService.WriteItem item : items) {
            UUID existingId = item.existing() == null ? null : item.existing().productId();
            if (item.request() == null) {
                result.add(new AppliedProduct(item.row().rowNumber(), item.row().rowNumbers(), existingId, existingId, false));
            } else if (existingId != null) {
                result.add(new AppliedProduct(item.row().rowNumber(), item.row().rowNumbers(), existingId,
                        updated.get(existingId).getId(), true));
            } else {
                Product product = createdByItem.get(item);
                result.add(new AppliedProduct(item.row().rowNumber(), item.row().rowNumbers(),
                        existingId, product.getId(), true));
            }
        }
        return List.copyOf(result);
    }

    public record AppliedProduct(int rowNumber, List<Integer> rowNumbers, UUID sourceId, UUID productId, boolean mutated) { }
    public static final class StaleVersionException extends IllegalStateException {
        public StaleVersionException(UUID productId, long expected, long actual) {
            super("Conflicto de version en el producto " + productId + ": se esperaba " + expected + " y tiene " + actual);
        }
    }
    /** Raised only for a revalidated, expected concurrent catalog conflict. */
    public static final class ConcurrentImportConflictException extends IllegalStateException {
        public ConcurrentImportConflictException(String message) {
            super(message);
        }
        public ConcurrentImportConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
