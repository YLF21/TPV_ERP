package com.tpverp.backend.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.inventory.StockLevelRepository;
import com.tpverp.backend.inventory.StockMovementRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.promotion.PromotionTargetRepository;
import com.tpverp.backend.promotion.PromotionTargetReference;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import com.tpverp.backend.shared.api.PagedResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class CatalogService {

    private final CurrentOrganization organization;
    private final StoreTaxRepository taxRepository;
    private final WarehouseRepository warehouseRepository;
    private final FamilyRepository familyRepository;
    private final SubfamilyRepository subfamilyRepository;
    private final ProductRepository productRepository;
    private FamilyProductPageRepository familyProductPageRepository;
    private final ProductIdentifierRepository identifierRepository;
    private final ProductPriceHistoryRepository priceHistoryRepository;
    private final StockLevelRepository stockRepository;
    private final StockMovementRepository movementRepository;
    private final PromotionTargetRepository promotionTargetRepository;
    private final ProductPriceRuleRepository productPriceRuleRepository;
    private final StoreRepository storeRepository;
    private final Clock clock;
    private AuditService auditService;

    public CatalogService(
            CurrentOrganization organization,
            StoreTaxRepository taxRepository,
            WarehouseRepository warehouseRepository,
            FamilyRepository familyRepository,
            SubfamilyRepository subfamilyRepository,
            ProductRepository productRepository,
            ProductIdentifierRepository identifierRepository,
            ProductPriceHistoryRepository priceHistoryRepository,
            StockLevelRepository stockRepository,
            StockMovementRepository movementRepository,
            Clock clock) {
        this(organization, taxRepository, warehouseRepository, familyRepository, subfamilyRepository,
                productRepository, identifierRepository, priceHistoryRepository, stockRepository,
                movementRepository, null, null, null, clock);
    }

    public CatalogService(
            CurrentOrganization organization,
            StoreTaxRepository taxRepository,
            WarehouseRepository warehouseRepository,
            FamilyRepository familyRepository,
            SubfamilyRepository subfamilyRepository,
            ProductRepository productRepository,
            ProductIdentifierRepository identifierRepository,
            ProductPriceHistoryRepository priceHistoryRepository,
            StockLevelRepository stockRepository,
            StockMovementRepository movementRepository,
            PromotionTargetRepository promotionTargetRepository,
            ProductPriceRuleRepository productPriceRuleRepository,
            StoreRepository storeRepository,
            Clock clock) {
        this.organization = organization;
        this.taxRepository = taxRepository;
        this.warehouseRepository = warehouseRepository;
        this.familyRepository = familyRepository;
        this.subfamilyRepository = subfamilyRepository;
        this.productRepository = productRepository;
        this.identifierRepository = identifierRepository;
        this.priceHistoryRepository = priceHistoryRepository;
        this.stockRepository = stockRepository;
        this.movementRepository = movementRepository;
        this.promotionTargetRepository = promotionTargetRepository;
        this.productPriceRuleRepository = productPriceRuleRepository;
        this.storeRepository = storeRepository;
        this.clock = clock;
    }

    /** Production wiring keeps catalog mutations auditable by construction. */
    @Autowired
    public CatalogService(
            CurrentOrganization organization,
            StoreTaxRepository taxRepository,
            WarehouseRepository warehouseRepository,
            FamilyRepository familyRepository,
            SubfamilyRepository subfamilyRepository,
            ProductRepository productRepository,
            ProductIdentifierRepository identifierRepository,
            ProductPriceHistoryRepository priceHistoryRepository,
            StockLevelRepository stockRepository,
            StockMovementRepository movementRepository,
            PromotionTargetRepository promotionTargetRepository,
            ProductPriceRuleRepository productPriceRuleRepository,
            StoreRepository storeRepository,
            Clock clock,
            FamilyProductPageRepository familyProductPageRepository,
            AuditService auditService) {
        this(organization, taxRepository, warehouseRepository, familyRepository, subfamilyRepository,
                productRepository, identifierRepository, priceHistoryRepository, stockRepository,
                movementRepository, promotionTargetRepository, productPriceRuleRepository,
                storeRepository, clock);
        this.familyProductPageRepository = Objects.requireNonNull(
                familyProductPageRepository, "familyProductPageRepository");
        this.auditService = java.util.Objects.requireNonNull(auditService, "auditService");
    }

    @Autowired(required = false)
    void setAuditService(AuditService auditService) {
        this.auditService = auditService;
    }

    void setFamilyProductPageRepository(FamilyProductPageRepository familyProductPageRepository) {
        this.familyProductPageRepository = Objects.requireNonNull(
                familyProductPageRepository, "familyProductPageRepository");
    }

    @Transactional(readOnly = true)
    public List<StoreTax> taxes() {
        return taxRepository.findByStoreIdOrderByPorcentaje(currentStore().getId());
    }

    /**
     * Validates export display maps against the authenticated store in two bulk
     * queries. The client may choose which rows to include, but never which
     * code is printed for a UUID-owned catalog record.
     */
    @Transactional(readOnly = true)
    public void validateBulkExportCodes(Map<String, String> familyCodes,
            Map<String, String> subfamilyCodes) {
        UUID storeId = currentStore().getId();
        validateFamilyExportCodes(storeId, familyCodes);
        validateSubfamilyExportCodes(storeId, subfamilyCodes);
    }

    /**
     * New XLSX clients send maps for the catalog rows they display. Require
     * coverage for every product row when a map is present, while retaining
     * the old empty-map contract for older clients.
     */
    @Transactional(readOnly = true)
    public void validateBulkExportCodes(List<ProductBulkEditContent.Row> content,
            Map<String, String> familyCodes, Map<String, String> subfamilyCodes) {
        resolveBulkExportCodes(content, familyCodes, subfamilyCodes);
    }

    /**
     * Resolves display codes from UUID-owned catalog rows when older XLSX
     * callers omit the optional maps. The authenticated store remains the
     * authority; client-provided maps are still checked when present.
     */
    @Transactional(readOnly = true)
    public BulkExportCodes resolveBulkExportCodes(List<ProductBulkEditContent.Row> content,
            Map<String, String> familyCodes, Map<String, String> subfamilyCodes) {
        UUID storeId = currentStore().getId();
        Map<String, String> resolvedFamilies = familyCodes == null || familyCodes.isEmpty()
                ? deriveFamilyExportCodes(storeId, content)
                : Map.copyOf(familyCodes);
        Map<String, String> resolvedSubfamilies = subfamilyCodes == null || subfamilyCodes.isEmpty()
                ? deriveSubfamilyExportCodes(storeId, content)
                : Map.copyOf(subfamilyCodes);
        validateBulkExportCodes(resolvedFamilies, resolvedSubfamilies);
        validateExportCoverage(content, resolvedFamilies, resolvedSubfamilies);
        return new BulkExportCodes(resolvedFamilies, resolvedSubfamilies);
    }

    public record BulkExportCodes(Map<String, String> familyCodes,
            Map<String, String> subfamilyCodes) {
    }

    private static void validateExportCoverage(List<ProductBulkEditContent.Row> content,
            Map<String, String> familyCodes, Map<String, String> subfamilyCodes) {
        if (content == null) {
            return;
        }
        content.stream().map(ProductBulkEditContent.Row::effectiveProduct)
                .filter(Objects::nonNull).map(ProductBulkEditContent.ProductData::familyId)
                .filter(CatalogService::hasCatalogReference).map(String::trim)
                .filter(id -> !familyCodes.containsKey(id))
                .findFirst().ifPresent(id -> {
                    throw new IllegalArgumentException("familyCodes no cubre todas las filas exportadas");
                });
        content.stream().map(ProductBulkEditContent.Row::effectiveProduct)
                .filter(Objects::nonNull).map(ProductBulkEditContent.ProductData::subfamilyId)
                .filter(CatalogService::hasCatalogReference).map(String::trim)
                .filter(id -> !subfamilyCodes.containsKey(id))
                .findFirst().ifPresent(id -> {
                    throw new IllegalArgumentException("subfamilyCodes no cubre todas las filas exportadas");
                });
    }

    private Map<String, String> deriveFamilyExportCodes(UUID storeId,
            List<ProductBulkEditContent.Row> content) {
        List<UUID> ids = idsFromContent(content, true);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return familyRepository.findByStoreIdAndIdIn(storeId, ids).stream()
                .collect(java.util.stream.Collectors.toMap(
                        family -> family.getId().toString(), Family::getFamilyCode));
    }

    private Map<String, String> deriveSubfamilyExportCodes(UUID storeId,
            List<ProductBulkEditContent.Row> content) {
        List<UUID> ids = idsFromContent(content, false);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return subfamilyRepository.findByStoreIdAndIdIn(storeId, ids).stream()
                .collect(java.util.stream.Collectors.toMap(
                        subfamily -> subfamily.getId().toString(), Subfamily::getSubfamilyCode));
    }

    private static List<UUID> idsFromContent(List<ProductBulkEditContent.Row> content, boolean families) {
        if (content == null) {
            return List.of();
        }
        return content.stream().map(ProductBulkEditContent.Row::effectiveProduct)
                .filter(Objects::nonNull)
                .map(product -> families ? product.familyId() : product.subfamilyId())
                .filter(CatalogService::hasCatalogReference).map(String::trim).distinct()
                .map(value -> {
                    try {
                        return UUID.fromString(value);
                    } catch (RuntimeException exception) {
                        throw new IllegalArgumentException("UUID de catalogo no valido", exception);
                    }
                }).toList();
    }

    private static boolean hasCatalogReference(String value) {
        return value != null && !value.isBlank() && !"-".equals(value.trim());
    }

    @Transactional(readOnly = true)
    public String nextFamilyCode() {
        return allocateFamilyCode(currentStore().getId());
    }

    @Transactional(readOnly = true)
    public String nextSubfamilySuffix(UUID familyId) {
        Family family = familyRepository.findById(familyId)
                .filter(value -> value.getStoreId().equals(currentStore().getId()))
                .orElseThrow(() -> new IllegalArgumentException("Familia no encontrada"));
        if (family.isDefaultFamily()) {
            throw new IllegalArgumentException("La familia GENERAL no admite subfamilias");
        }
        return allocateSubfamilySuffix(family.getId());
    }

    public record NextFamilyCode(String familyCode) {
    }

    public record NextSubfamilySuffix(String subfamilySuffix) {
    }

    private void validateFamilyExportCodes(UUID storeId, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        Map<UUID, Family> actual = familyRepository.findByStoreIdAndIdIn(
                        storeId, values.keySet().stream().map(UUID::fromString).toList())
                .stream().collect(java.util.stream.Collectors.toMap(Family::getId, value -> value));
        if (actual.size() != values.size()) {
            throw new IllegalArgumentException("familyCodes contiene una familia fuera de la tienda actual");
        }
        values.forEach((id, code) -> {
            Family family = actual.get(UUID.fromString(id));
            if (family == null || code == null
                    || !family.getFamilyCode().equals(code.trim())) {
                throw new IllegalArgumentException("familyCodes no coincide con el catalogo actual");
            }
        });
    }

    private void validateSubfamilyExportCodes(UUID storeId, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        Map<UUID, Subfamily> actual = subfamilyRepository.findByStoreIdAndIdIn(
                        storeId, values.keySet().stream().map(UUID::fromString).toList())
                .stream().collect(java.util.stream.Collectors.toMap(Subfamily::getId, value -> value));
        if (actual.size() != values.size()) {
            throw new IllegalArgumentException("subfamilyCodes contiene una subfamilia fuera de la tienda actual");
        }
        values.forEach((id, code) -> {
            Subfamily subfamily = actual.get(UUID.fromString(id));
            if (subfamily == null || code == null
                    || !subfamily.getSubfamilyCode().equals(code.trim())) {
                throw new IllegalArgumentException("subfamilyCodes no coincide con el catalogo actual");
            }
        });
    }

    @Transactional(readOnly = true)
    public List<StoreTax> selectableTaxes() {
        return taxes().stream().filter(StoreTax::isActive).toList();
    }

    @Transactional
    public StoreTax createTax(BigDecimal percentage) {
        UUID storeId = currentStore().getId();
        if (taxRepository.findByStoreIdAndPorcentaje(storeId, percentage).isPresent()) {
            throw new IllegalArgumentException("Ya existe ese porcentaje de impuesto");
        }
        return taxRepository.save(new StoreTax(storeId, percentage, false));
    }

    @Transactional
    public StoreTax updateTax(UUID taxId, BigDecimal percentage) {
        StoreTax tax = tax(taxId);
        taxRepository.findByStoreIdAndPorcentaje(tax.getStoreId(), percentage)
                .filter(existing -> !existing.getId().equals(taxId))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Ya existe ese porcentaje de impuesto");
                });
        tax.replacePercentage(percentage);
        return tax;
    }

    @Transactional
    public void deleteTax(UUID taxId) {
        StoreTax tax = tax(taxId);
        tax.requireDeletable();
        if (productRepository.existsByTaxId(taxId)) {
            throw new IllegalStateException("No se puede eliminar un impuesto utilizado por productos");
        }
        taxRepository.delete(tax);
    }

    @Transactional
    public StoreTax setDefaultTax(UUID taxId) {
        StoreTax selected = tax(taxId);
        taxRepository.findByStoreIdAndPredeterminadoTrue(selected.getStoreId())
                .filter(current -> !current.getId().equals(selected.getId()))
                .ifPresent(StoreTax::clearDefault);
        selected.markDefault();
        return selected;
    }

    @Transactional
    public StoreTax setTaxActive(UUID taxId, boolean active) {
        StoreTax tax = tax(taxId);
        if (active) {
            tax.activate();
        } else {
            if (productRepository.existsByTaxId(taxId)) {
                throw new IllegalStateException("No se puede desactivar un impuesto utilizado por productos");
            }
            tax.deactivate();
        }
        return tax;
    }

    @Transactional(readOnly = true)
    public List<Warehouse> warehouses() {
        return warehouseRepository.findByStoreIdOrderByNombre(currentStore().getId());
    }

    @Transactional
    public Warehouse createWarehouse(String name) {
        UUID storeId = currentStore().getId();
        String normalized = CatalogText.normalized(name, "nombre");
        if (warehouseRepository.existsByStoreIdAndNombreIgnoreCase(storeId, normalized)) {
            throw new IllegalArgumentException("Ya existe un almacen con ese nombre");
        }
        return warehouseRepository.save(new Warehouse(storeId, normalized));
    }

    @Transactional
    public Warehouse renameWarehouse(UUID warehouseId, String name) {
        Warehouse warehouse = warehouse(warehouseId);
        String normalized = CatalogText.normalized(name, "nombre");
        if (!warehouse.getName().equals(normalized)
                && warehouseRepository.existsByStoreIdAndNombreIgnoreCase(warehouse.getStoreId(), normalized)) {
            throw new IllegalArgumentException("Ya existe un almacen con ese nombre");
        }
        warehouse.rename(normalized);
        return warehouse;
    }

    @Transactional
    public Warehouse setWarehouseActive(UUID warehouseId, boolean active) {
        Warehouse warehouse = warehouse(warehouseId);
        if (active) {
            warehouse.activate();
        } else {
            warehouse.deactivate(stockRepository.sumQuantityByWarehouseId(warehouseId));
        }
        return warehouse;
    }

    @Transactional
    public void deleteWarehouse(UUID warehouseId) {
        Warehouse warehouse = warehouse(warehouseId);
        warehouse.deactivate(stockRepository.sumQuantityByWarehouseId(warehouseId));
        warehouseRepository.delete(warehouse);
    }

    @Transactional(readOnly = true)
    public List<Family> families() {
        return familiesOrdered(currentStore().getId());
    }

    @Transactional(readOnly = true)
    public List<Subfamily> subfamilies(UUID familyId) {
        family(familyId);
        return subfamiliesOrdered(familyId);
    }

    /**
     * Bounded, store-scoped listing for the family tree. Exactly one filter is
     * required: family includes direct products and all its subfamilies;
     * subfamily includes only exact children. Inactive products are included.
     */
    @Transactional(readOnly = true)
    public PagedResult<FamilyProductView> familyProducts(
            UUID familyId, UUID subfamilyId, Integer requestedLimit, String cursor) {
        return familyProducts(familyId, subfamilyId, requestedLimit, cursor, null, null);
    }

    @Transactional(readOnly = true)
    public PagedResult<FamilyProductView> familyProducts(
            UUID familyId,
            UUID subfamilyId,
            Integer requestedLimit,
            String cursor,
            String sortBy,
            String sortDirection) {
        if ((familyId == null) == (subfamilyId == null)) {
            throw new IllegalArgumentException("Debe indicar exactamente familyId o subfamilyId");
        }
        UUID storeId = currentStore().getId();
        FamilyProductPageRepository.ScopeKind scopeKind;
        UUID scopeId;
        if (familyId != null) {
            family(familyId);
            scopeKind = FamilyProductPageRepository.ScopeKind.FAMILY;
            scopeId = familyId;
        } else {
            subfamily(subfamilyId);
            scopeKind = FamilyProductPageRepository.ScopeKind.SUBFAMILY;
            scopeId = subfamilyId;
        }
        int limit = requestedLimit == null ? 50 : requestedLimit;
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit debe estar entre 1 y 100");
        }
        String normalizedSort = normalizeFamilyProductSort(sortBy);
        String normalizedDirection = normalizeFamilyProductSortDirection(sortDirection);
        ProductCursor decoded = decodeProductCursor(
                cursor, normalizedSort, normalizedDirection, scopeKind, scopeId);
        FamilyProductPageRepository queryRepository = Objects.requireNonNull(
                familyProductPageRepository, "familyProductPageRepository");
        List<FamilyProductPageRepository.FamilyProductPageRow> rows = queryRepository.findPage(
                storeId, scopeKind, scopeId, normalizedSort, normalizedDirection,
                decoded == null ? null : new FamilyProductPageRepository.FamilyProductPageCursor(
                        decoded.nullSortValue(), decoded.value(), decoded.id()),
                limit + 1);
        boolean hasMore = rows.size() > limit;
        List<FamilyProductPageRepository.FamilyProductPageRow> visible = hasMore
                ? rows.subList(0, limit) : rows;
        List<FamilyProductView> items = visible.stream().map(row -> new FamilyProductView(
                row.id(), row.version(), row.imageId(), row.imageHash(), row.code(),
                row.barcode(), row.name(), row.salePrice(), row.familyId(), row.subfamilyId(),
                row.active())).toList();
        String next = hasMore && !items.isEmpty()
                ? encodeProductCursor(visible.getLast(), normalizedSort, normalizedDirection,
                        scopeKind, scopeId)
                : null;
        return new PagedResult<>(items, next, hasMore);
    }

    /** Searches both hierarchy levels in one bounded, store-scoped query. */
    @Transactional(readOnly = true)
    public PagedResult<FamilyHierarchySearchView> searchHierarchy(
        String query, Integer requestedLimit, String cursor) {
        String term = CatalogText.searchTerm(query);
        UUID storeId = currentStore().getId();
        int queryLength = term.codePointCount(0, term.length());
        if (queryLength < 2 || queryLength > 100) {
            throw new IllegalArgumentException("q debe contener entre 2 y 100 caracteres");
        }
        int limit = requestedLimit == null ? 50 : requestedLimit;
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit debe estar entre 1 y 100");
        }
        HierarchySearchCursor decoded = decodeHierarchySearchCursor(cursor);
        List<FamilyHierarchySearchProjection> rows = familyRepository.searchHierarchy(
                storeId, CatalogText.escapeLikeLiteral(term), queryLength,
                decoded == null ? null : decoded.kind(),
                decoded == null ? null : decoded.code(),
                decoded == null ? null : decoded.id(), limit + 1);
        boolean hasMore = rows.size() > limit;
        List<FamilyHierarchySearchProjection> visible = hasMore ? rows.subList(0, limit) : rows;
        List<FamilyHierarchySearchView> items = visible.stream()
                .map(row -> new FamilyHierarchySearchView(row.getKind(), row.getId(), row.getFamilyId(),
                        row.getSubfamilyId(), row.getCode(), row.getName(), row.getFamilyCode(),
                        row.getSuffix(), row.isDefaultFamily()))
                .toList();
        String next = hasMore && !items.isEmpty()
                ? encodeHierarchySearchCursor(items.getLast().kind(), items.getLast().code(),
                        items.getLast().id()) : null;
        return new PagedResult<>(items, next, hasMore);
    }

    /** Atomically classifies up to 5,000 products with pessimistic row locks. */
    @Transactional
    public BulkMoveResult moveProducts(BulkMoveRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("items es obligatorio");
        }
        if (request.items().size() > 5000) {
            throw new IllegalArgumentException("No se pueden mover mas de 5000 productos");
        }
        Set<UUID> ids = new HashSet<>();
        request.items().forEach(item -> {
            if (item == null || item.productId() == null || item.expectedVersion() == null
                    || !ids.add(item.productId())) {
                throw new IllegalArgumentException("items debe contener productos unicos y version esperada");
            }
        });
        UUID storeId = currentStore().getId();
        lockStoreForCatalogMutation(storeId);
        Family destination;
        Subfamily destinationSubfamily = null;
        if (request.subfamilyId() != null) {
            destinationSubfamily = subfamily(request.subfamilyId());
            destination = family(destinationSubfamily.getFamilyId());
        } else if (request.familyId() != null) {
            destination = family(request.familyId());
        } else {
            destination = familyRepository.findByStoreIdAndPredeterminadaTrue(storeId)
                    .orElseThrow(() -> new IllegalStateException("La familia GENERAL no esta inicializada"));
        }
        final Family targetFamily = destination;
        final Subfamily targetSubfamily = destinationSubfamily;
        List<Product> products = productRepository.findAllByStoreIdAndIdInForUpdate(storeId, ids);
        if (products.size() != ids.size()) {
            throw new IllegalArgumentException("Hay productos inexistentes o fuera de la tienda actual");
        }
        Map<UUID, Long> expected = request.items().stream()
                .collect(java.util.stream.Collectors.toMap(MoveProductItem::productId,
                        MoveProductItem::expectedVersion));
        List<ProductClassificationVersionConflictException.Conflict> conflicts = products.stream()
                .filter(product -> product.getVersion() != expected.get(product.getId()))
                .map(product -> new ProductClassificationVersionConflictException.Conflict(
                        product.getId(), expected.get(product.getId()), product.getVersion()))
                .sorted(java.util.Comparator.comparing(ProductClassificationVersionConflictException.Conflict::productId))
                .toList();
        if (!conflicts.isEmpty()) {
            throw new ProductClassificationVersionConflictException(conflicts);
        }
        List<Product> orderedProducts = products.stream()
                .sorted(java.util.Comparator.comparing(Product::getId))
                .toList();
        List<Map<String, Object>> changes = orderedProducts.stream()
                .map(product -> {
                    Map<String, Object> change = new LinkedHashMap<>();
                    change.put("productId", product.getId().toString());
                    Map<String, Object> before = new LinkedHashMap<>();
                    before.put("familyId", product.getFamilyId().toString());
                    before.put("subfamilyId", product.getSubfamilyId() == null
                            ? null : product.getSubfamilyId().toString());
                    before.put("version", product.getVersion());
                    change.put("before", before);
                    Map<String, Object> after = new LinkedHashMap<>();
                    after.put("familyId", targetFamily.getId().toString());
                    after.put("subfamilyId", targetSubfamily == null
                            ? null : targetSubfamily.getId().toString());
                    after.put("version", product.getVersion() + 1);
                    change.put("after", after);
                    return change;
                })
                .toList();
        int updated = productRepository.moveClassification(storeId, ids, targetFamily.getId(),
                targetSubfamily == null ? null : targetSubfamily.getId());
        if (updated != ids.size()) {
            throw new IllegalStateException(
                    "No se actualizaron todos los productos solicitados; la operación se ha revertido");
        }
        if (auditService != null) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("productCount", updated);
            details.put("productIds", orderedProducts.stream().map(Product::getId).map(UUID::toString).toList());
            details.put("familyId", targetFamily.getId().toString());
            details.put("subfamilyId", targetSubfamily == null ? null : targetSubfamily.getId().toString());
            details.put("changes", changes);
            auditService.record("PRODUCT_CLASSIFICATION_BULK_MOVED", AuditResult.EXITO, details);
        }
        return new BulkMoveResult(ids.size(), updated, targetFamily.getId(),
                targetSubfamily == null ? null : targetSubfamily.getId());
    }

    @Transactional
    public Family createFamily(String name) {
        return createFamily(name, null);
    }

    @Transactional
    public Family createFamily(String name, String requestedCode) {
        UUID storeId = currentStore().getId();
        lockStoreForCatalogMutation(storeId);
        String normalized = CatalogText.normalized(name, "nombre");
        if (familyRepository.existsByStoreIdAndNombreIgnoreCase(storeId, normalized)) {
            throw new IllegalArgumentException("Ya existe una familia con ese nombre");
        }
        Family family = new Family(storeId, normalized, false);
        family.assignCode(requestedCode == null
                        ? allocateFamilyCode(storeId)
                        : requestedFamilyCode(storeId, requestedCode));
        Family saved = familyRepository.save(family);
        auditFamily("FAMILY_CREATED", null, saved.getName(), saved);
        return saved;
    }

    @Transactional
    public Family renameFamily(UUID familyId, String name) {
        return renameFamily(familyId, name, null);
    }

    @Transactional
    public Family renameFamily(UUID familyId, String name, String requestedCode) {
        Family family = family(familyId);
        if (requestedCode != null
                && !family.getFamilyCode().equals(normalizeNumericCode(requestedCode, "familyCode"))) {
            throw new IllegalArgumentException("familyCode es inmutable");
        }
        String normalized = CatalogText.normalized(name, "nombre");
        if (!family.getName().equals(normalized)
                && familyRepository.existsByStoreIdAndNombreIgnoreCaseAndIdNot(
                        family.getStoreId(), normalized, family.getId())) {
            throw new IllegalArgumentException("Ya existe una familia con ese nombre");
        }
        String beforeName = family.getName();
        family.rename(name);
        auditFamily("FAMILY_RENAMED", beforeName, family.getName(), family);
        return family;
    }

    @Transactional
    public void deleteFamily(UUID familyId) {
        deleteFamily(familyId, false);
    }

    @Transactional
    public void deleteFamily(UUID familyId, boolean confirmProductReassignment) {
        UUID storeId = currentStore().getId();
        lockStoreForCatalogMutation(storeId);
        Family family = familyForUpdate(familyId);
        family.requireDeletable();
        DeleteImpact impact = familyDeleteImpact(family);
        if (impact.blocked()) {
            throw new IllegalStateException("No se puede eliminar la familia porque tiene referencias activas");
        }
        if (impact.productCount() > 0 && !confirmProductReassignment) {
            throw new IllegalStateException(
                    "La familia tiene productos; confirma la reasignacion a GENERAL");
        }
        Family general = familyRepository.findByStoreIdAndPredeterminadaTrue(family.getStoreId())
                .orElseThrow(() -> new IllegalStateException("La familia GENERAL no esta inicializada"));
        productRepository.reassignFamilyToGeneral(familyId, general.getId());
        requireFullyUnreferenced(familyDeleteImpact(family), "familia");
        auditFamily("FAMILY_DELETED", family.getName(), null, family);
        familyRepository.delete(family);
    }

    @Transactional
    public Subfamily createSubfamily(UUID familyId, String name) {
        return createSubfamily(familyId, name, null);
    }

    @Transactional
    public Subfamily createSubfamily(UUID familyId, String name, String requestedSuffix) {
        UUID storeId = currentStore().getId();
        lockStoreForCatalogMutation(storeId);
        Family family = family(familyId);
        if (family.isDefaultFamily()) {
            throw new IllegalArgumentException("La familia GENERAL no admite subfamilias");
        }
        String normalized = CatalogText.normalized(name, "nombre");
        if (subfamilyRepository.existsByFamilyIdAndNombreIgnoreCase(familyId, normalized)) {
            throw new IllegalArgumentException("Ya existe una subfamilia con ese nombre");
        }
        Subfamily subfamily = new Subfamily(familyId, normalized);
        subfamily.assignCode(family.getFamilyCode(), requestedSuffix == null
                        ? allocateSubfamilySuffix(familyId)
                        : requestedSubfamilySuffix(familyId, requestedSuffix));
        Subfamily saved = subfamilyRepository.save(subfamily);
        auditSubfamily("SUBFAMILY_CREATED", null, saved.getName(), saved);
        return saved;
    }

    @Transactional
    public Subfamily renameSubfamily(UUID subfamilyId, String name) {
        return renameSubfamily(subfamilyId, name, null);
    }

    @Transactional
    public Subfamily renameSubfamily(UUID subfamilyId, String name, String requestedSuffix) {
        Subfamily subfamily = subfamily(subfamilyId);
        if (requestedSuffix != null
                && !subfamily.getSubfamilySuffix().equals(
                        normalizeNumericCode(requestedSuffix, "subfamilySuffix"))) {
            throw new IllegalArgumentException("subfamilySuffix es inmutable");
        }
        String normalized = CatalogText.normalized(name, "nombre");
        if (!subfamily.getName().equals(normalized)
                && subfamilyRepository.existsByFamilyIdAndNombreIgnoreCaseAndIdNot(
                        subfamily.getFamilyId(), normalized, subfamily.getId())) {
            throw new IllegalArgumentException("Ya existe una subfamilia con ese nombre");
        }
        String beforeName = subfamily.getName();
        subfamily.rename(name);
        auditSubfamily("SUBFAMILY_RENAMED", beforeName, subfamily.getName(), subfamily);
        return subfamily;
    }

    @Transactional
    public void deleteSubfamily(UUID subfamilyId) {
        deleteSubfamily(subfamilyId, false);
    }

    @Transactional
    public void deleteSubfamily(UUID subfamilyId, boolean confirmProductCleanup) {
        UUID storeId = currentStore().getId();
        lockStoreForCatalogMutation(storeId);
        Subfamily subfamily = subfamilyForUpdate(subfamilyId);
        DeleteImpact impact = subfamilyDeleteImpact(subfamily);
        if (impact.blocked()) {
            throw new IllegalStateException("No se puede eliminar la subfamilia porque tiene referencias activas");
        }
        if (impact.productCount() > 0 && !confirmProductCleanup) {
            throw new IllegalStateException(
                    "La subfamilia tiene productos; confirma la limpieza de sus referencias");
        }
        productRepository.clearSubfamilyReferences(subfamilyId);
        requireFullyUnreferenced(subfamilyDeleteImpact(subfamily), "subfamilia");
        auditSubfamily("SUBFAMILY_DELETED", subfamily.getName(), null, subfamily);
        subfamilyRepository.delete(subfamily);
    }

    @Transactional(readOnly = true)
    public void validatePriceRuleCatalogReferences(
            List<ProductPriceRuleForm.Definition> forms) {
        List<ProductPriceRuleForm.Definition> values =
                ProductPriceRuleForm.validateAndCopy(forms);
        Set<UUID> familyIds = new HashSet<>();
        Set<UUID> subfamilyIds = new HashSet<>();
        for (ProductPriceRuleForm.Definition form : values) {
            for (ProductPriceRuleForm.Condition condition : form.conditions()) {
                if (condition instanceof ProductPriceRuleForm.ReferenceCondition reference) {
                    if (reference.field() == ProductPriceRuleForm.ReferenceField.FAMILY) {
                        familyIds.addAll(reference.values());
                    } else if (reference.field() == ProductPriceRuleForm.ReferenceField.SUBFAMILY) {
                        subfamilyIds.addAll(reference.values());
                    }
                }
            }
        }
        UUID storeId = currentStore().getId();
        if (!familyIds.isEmpty()) {
            Set<UUID> found = familyRepository.findByStoreIdAndIdIn(storeId, familyIds).stream()
                    .map(Family::getId).collect(java.util.stream.Collectors.toSet());
            if (!found.equals(familyIds)) {
                throw new IllegalArgumentException(
                        "Una familia de la regla no pertenece a la tienda actual");
            }
        }
        if (!subfamilyIds.isEmpty()) {
            Set<UUID> found = subfamilyRepository.findByStoreIdAndIdIn(storeId, subfamilyIds).stream()
                    .map(Subfamily::getId).collect(java.util.stream.Collectors.toSet());
            if (!found.equals(subfamilyIds)) {
                throw new IllegalArgumentException(
                        "Una subfamilia de la regla no pertenece a la tienda actual");
            }
        }
    }

    @Transactional(readOnly = true)
    public FamilyResolution resolve(String code) {
        String normalized = normalizeCode(code);
        UUID storeId = currentStore().getId();
        Family family = null;
        Subfamily subfamily = null;
        if (normalized.matches("[0-9]{3}")) {
            family = familyRepository.findByStoreIdAndFamilyCode(storeId, normalized)
                    .orElseThrow(() -> new IllegalArgumentException("Familia no encontrada"));
        } else if (normalized.matches("[0-9]{6}")) {
            subfamily = subfamilyRepository.findByStoreIdAndSubfamilyCode(storeId, normalized)
                    .orElseThrow(() -> new IllegalArgumentException("Subfamilia no encontrada"));
            family = family(subfamily.getFamilyId());
        } else {
            throw new IllegalArgumentException("El codigo operativo debe tener 3 o 6 digitos");
        }
        return FamilyResolution.from(family, subfamily);
    }

    @Transactional(readOnly = true)
    public DeleteImpact familyDeleteImpact(UUID familyId) {
        return familyDeleteImpact(family(familyId));
    }

    @Transactional(readOnly = true)
    public DeleteImpact subfamilyDeleteImpact(UUID subfamilyId) {
        return subfamilyDeleteImpact(subfamily(subfamilyId));
    }

    @Transactional(readOnly = true)
    public List<Product> products() {
        return productRepository.findByStoreIdOrderByNombre(currentStore().getId()).stream()
                .map(CatalogService::initializeProductForApi)
                .toList();
    }

    @Transactional(readOnly = true)
    public Product product(UUID productId) {
        return initializeProductForApi(sameStore(productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Producto no encontrado"))));
    }

    @Transactional
    public Product createProduct(ProductRequest request) {
        UUID storeId = currentStore().getId();
        lockStoreForCatalogMutation(storeId);
        return createProductLocked(storeId, request);
    }

    private Product createProductLocked(UUID storeId, ProductRequest request) {
        validateProductRequest(null, storeId, request);
        Product product = new Product(
                storeId, request.familyId(), request.subfamilyId(), request.taxId(),
                request.productType(), request.discountType(), request.name(), request.description(),
                request.comments(), request.purchasePrice(), request.taxesIncluded());
        applyProductData(product, request);
        Product saved = productRepository.saveAndFlush(product);
        recordInitialPrices(saved);
        return saved;
    }

    @Transactional
    public Product createProductWithPrimaryBarcode(
            ProductRequest request,
            String barcode) {
        return createProduct(request.withPrimaryBarcode(barcode));
    }

    @Transactional
    public Product assignSecondaryBarcode(UUID productId, String barcode) {
        Product product = product(productId);
        var normalized = barcode == null ? "" : barcode.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("internal_ean_code_required");
        }
        validateIdentifiers(product.getStoreId(), productId, normalized);
        product.replaceIdentifier(IdentifierType.CODIGO_BARRAS_2, normalized);
        return productRepository.saveAndFlush(product);
    }

    @Transactional
    public Product updateProduct(UUID productId, ProductRequest request) {
        UUID storeId = currentStore().getId();
        lockStoreForCatalogMutation(storeId);
        return updateProductLocked(productForUpdate(storeId, productId), request);
    }

    private Product updateProductLocked(Product product, ProductRequest request) {
        if (isOpenPriceProduct(product)
                && (request.code() == null || !"0".equals(request.code().trim()))) {
            throw new IllegalArgumentException("product_zero_code_reserved");
        }
        validateProductRequest(product.getId(), product.getStoreId(), request);
        PriceSnapshot before = PriceSnapshot.from(product);
        product.update(
                request.familyId(), request.subfamilyId(), request.taxId(), request.productType(),
                request.discountType(), request.name(), request.description(), request.comments(),
                request.purchasePrice(), request.taxesIncluded());
        applyProductData(product, request);
        recordChangedPrices(product, before);
        return product;
    }

    @Transactional
    public Product setProductActive(UUID productId, boolean active) {
        Product product = product(productId);
        product.setActive(active);
        return product;
    }

    @Transactional(readOnly = true)
    public void validateProductUpdate(UUID productId, ProductRequest request) {
        Product product = product(productId);
        validateProductRequest(productId, product.getStoreId(), request);
    }

    @Transactional
    public Product createOrUpdateFromImport(ProductRequest request, UUID existingProductId) {
        UUID storeId = currentStore().getId();
        lockStoreForCatalogMutation(storeId);
        return existingProductId == null
                ? createProductLocked(storeId, request)
                : updateProductLocked(productForUpdate(storeId, existingProductId), request);
    }

    @Transactional(readOnly = true)
    public List<ProductPriceHistory> priceHistory(UUID productId) {
        Product product = product(productId);
        return priceHistoryRepository.findByProductIdOrderByUpdatedAtDesc(product.getId());
    }

    @Transactional
    public void deleteProduct(UUID productId) {
        Product product = product(productId);
        if (isOpenPriceProduct(product)) {
            throw new IllegalStateException("product_zero_cannot_delete");
        }
        if (stockRepository.existsByProductId(productId) || movementRepository.existsByProductId(productId)) {
            throw new IllegalStateException("No se puede eliminar un producto con historial");
        }
        productRepository.delete(product);
    }

    private static boolean isOpenPriceProduct(Product product) {
        return "0".equals(product.getCode());
    }

    private void applyProductData(Product product, ProductRequest request) {
        validateDiscountType(request);
        if (request.code() != null && !request.code().isBlank()) {
            product.replaceIdentifier(IdentifierType.CODIGO, request.code());
        } else {
            product.removeIdentifier(IdentifierType.CODIGO);
        }
        if (request.barcode() != null && !request.barcode().isBlank()) {
            product.replaceIdentifier(IdentifierType.CODIGO_BARRAS, request.barcode());
        } else {
            product.removeIdentifier(IdentifierType.CODIGO_BARRAS);
        }
        if (request.barcode2() != null && !request.barcode2().isBlank()) {
            product.replaceIdentifier(IdentifierType.CODIGO_BARRAS_2, request.barcode2());
        } else {
            product.removeIdentifier(IdentifierType.CODIGO_BARRAS_2);
        }
        product.setPrice(PriceTier.VENTA, request.salePrice());
        product.setPrice(PriceTier.MEMBER, request.memberPrice());
        product.setPrice(PriceTier.MAYORISTA, request.wholesalePrice());
        product.setPrice(PriceTier.OFERTA, offerPrice(request));
        product.configurePriceUse(request.priceUseMode(), request.offerDiscountPercent());
        product.configurePurchaseDiscount(request.purchaseDiscountPercent());
        product.configureStockLimits(request.stockMin(), request.stockMax());
        product.configurePackageQuantity(request.packageQuantity());
        if (request.requiresSerialNumber() != null || request.productType() != ProductType.UNIT) {
            product.configureSerialNumberTracking(
                    request.productType() == ProductType.UNIT
                            && Boolean.TRUE.equals(request.requiresSerialNumber()));
        }
        if (request.active() != null) {
            product.setActive(request.active());
        }
        product.configureOffer(offerActive(request), request.offerFrom(), request.offerUntil());
    }

    private void validateProductRequest(UUID productId, UUID storeId, ProductRequest request) {
        Objects.requireNonNull(request, "product");
        validateReferences(request, storeId);
        validateRequiredProductIdentifier(request);
        validateIdentifiers(storeId, productId, request.code(), request.barcode(), request.barcode2());
        if (request.productType() != ProductType.UNIT
                && Boolean.TRUE.equals(request.requiresSerialNumber())) {
            throw new IllegalArgumentException("message.product.serial_number_requires_unit");
        }
        Product candidate = new Product(
                storeId,
                request.familyId(),
                request.subfamilyId(),
                request.taxId(),
                request.productType(),
                request.discountType(),
                request.name(),
                request.description(),
                request.comments(),
                request.purchasePrice(),
                request.taxesIncluded());
        applyProductData(candidate, request);
    }

    private static void validateRequiredProductIdentifier(ProductRequest request) {
        boolean hasCode = request.code() != null && !request.code().isBlank();
        boolean hasBarcode = request.barcode() != null && !request.barcode().isBlank();
        if (!hasCode && !hasBarcode) {
            throw new IllegalArgumentException("message.product.code_or_barcode_required");
        }
    }

    private static void validateDiscountType(ProductRequest request) {
        if (isOfferPriceUseMode(request.priceUseMode())
                && (offerPrice(request) == null || request.offerFrom() == null)) {
            throw new IllegalArgumentException("message.product.discount_price_requires_offer");
        }
        if (request.priceUseMode() == PriceUseMode.OFFER_DISCOUNT && request.offerDiscountPercent() == null) {
            throw new IllegalArgumentException("message.product.offer_discount_requires_percent");
        }
    }

    private static boolean offerActive(ProductRequest request) {
        return isOfferPriceUseMode(request.priceUseMode()) || request.offerActive();
    }

    private static boolean isOfferPriceUseMode(PriceUseMode mode) {
        return mode == PriceUseMode.OFFER_PRICE || mode == PriceUseMode.OFFER_DISCOUNT;
    }

    private static BigDecimal offerPrice(ProductRequest request) {
        if (request.priceUseMode() == PriceUseMode.OFFER_DISCOUNT
                && request.offerPrice() == null
                && request.salePrice() != null
                && request.offerDiscountPercent() != null) {
            return request.salePrice()
                    .subtract(request.salePrice().multiply(request.offerDiscountPercent())
                            .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP))
                    .max(BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP);
        }
        return request.offerPrice();
    }

    private static DiscountType discountTypeFromPriceUseMode(PriceUseMode mode) {
        return discountTypeFromPriceUseMode(mode, null);
    }

    private static DiscountType discountTypeFromPriceUseMode(PriceUseMode mode, DiscountType requestedDiscountType) {
        if (mode == PriceUseMode.NORMAL && requestedDiscountType == DiscountType.NONE) {
            return DiscountType.NONE;
        }
        if (mode == PriceUseMode.MEMBER_PRICE) {
            return DiscountType.MEMBER_PRICE;
        }
        if (isOfferPriceUseMode(mode)) {
            return DiscountType.DISCOUNT_PRICE;
        }
        return DiscountType.NORMAL;
    }

    private static PriceUseMode priceUseModeFromDiscountType(DiscountType discountType) {
        if (discountType == DiscountType.MEMBER_PRICE) {
            return PriceUseMode.MEMBER_PRICE;
        }
        if (discountType == DiscountType.DISCOUNT_PRICE) {
            return PriceUseMode.OFFER_PRICE;
        }
        return PriceUseMode.NORMAL;
    }

    private void recordInitialPrices(Product product) {
        var now = Instant.now(clock);
        var entries = new ArrayList<ProductPriceHistory>();
        addHistory(entries, product, ProductPriceHistoryType.COSTE, product.getPurchasePrice(), now);
        addHistory(entries, product, ProductPriceHistoryType.VENTA, product.getSalePrice(), now);
        addHistory(entries, product, ProductPriceHistoryType.MEMBER, product.getMemberPrice(), now);
        addHistory(entries, product, ProductPriceHistoryType.MAYORISTA, product.getWholesalePrice(), now);
        addHistory(entries, product, ProductPriceHistoryType.OFERTA, product.getOfferPrice(), now);
        saveHistory(entries);
    }

    private void recordChangedPrices(Product product, PriceSnapshot before) {
        var now = Instant.now(clock);
        var entries = new ArrayList<ProductPriceHistory>();
        addChangedHistory(
                entries, product, ProductPriceHistoryType.COSTE,
                before.purchasePrice(), product.getPurchasePrice(), now);
        addChangedHistory(
                entries, product, ProductPriceHistoryType.VENTA,
                before.salePrice(), product.getSalePrice(), now);
        addChangedHistory(
                entries, product, ProductPriceHistoryType.MEMBER,
                before.memberPrice(), product.getMemberPrice(), now);
        addChangedHistory(
                entries, product, ProductPriceHistoryType.MAYORISTA,
                before.wholesalePrice(), product.getWholesalePrice(), now);
        addChangedHistory(
                entries, product, ProductPriceHistoryType.OFERTA,
                before.offerPrice(), product.getOfferPrice(), now);
        saveHistory(entries);
    }

    private void addChangedHistory(
            List<ProductPriceHistory> entries,
            Product product,
            ProductPriceHistoryType type,
            BigDecimal before,
            BigDecimal after,
            Instant updatedAt) {
        if (!sameAmount(before, after)) {
            addHistory(entries, product, type, after, updatedAt);
        }
    }

    private void addHistory(
            List<ProductPriceHistory> entries,
            Product product,
            ProductPriceHistoryType type,
            BigDecimal amount,
            Instant updatedAt) {
        if (amount != null || type == ProductPriceHistoryType.COSTE || type == ProductPriceHistoryType.VENTA) {
            entries.add(new ProductPriceHistory(product.getId(), type, amount, updatedAt));
        }
    }

    private static boolean sameAmount(BigDecimal first, BigDecimal second) {
        if (first == null || second == null) {
            return first == second;
        }
        return first.compareTo(second) == 0;
    }

    private void saveHistory(List<ProductPriceHistory> entries) {
        if (!entries.isEmpty()) {
            priceHistoryRepository.saveAll(entries);
        }
    }

    private void validateReferences(ProductRequest request, UUID storeId) {
        Family family = family(request.familyId());
        if (!family.getStoreId().equals(storeId)) {
            throw new IllegalArgumentException("La familia no pertenece a la tienda");
        }
        if (request.subfamilyId() != null) {
            Subfamily subfamily = subfamily(request.subfamilyId());
            if (!subfamily.getFamilyId().equals(request.familyId())) {
                throw new IllegalArgumentException("La subfamilia no pertenece a la familia");
            }
        }
        StoreTax tax = tax(request.taxId());
        if (!tax.getStoreId().equals(storeId)) {
            throw new IllegalArgumentException("El impuesto no pertenece a la tienda");
        }
        tax.requireSelectable();
    }

    private void validateIdentifiers(UUID storeId, UUID productId, String... values) {
        var currentValues = new java.util.HashMap<String, Integer>();
        for (int index = 0; index < values.length; index++) {
            String value = values[index];
            if (value == null || value.isBlank()) {
                continue;
            }
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            Integer previousIndex = currentValues.putIfAbsent(normalized, index);
            boolean samePrimaryIdentifier = previousIndex != null && previousIndex <= 1 && index <= 1;
            if (previousIndex != null && !samePrimaryIdentifier) {
                throw new IllegalArgumentException("El identificador ya esta asignado a otro producto");
            }
            boolean collision = productId == null
                    ? identifierRepository.findByStoreIdAndValor(storeId, normalized).isPresent()
                    : identifierRepository.existsByStoreIdAndValorAndProductIdNot(storeId, normalized, productId);
            if (collision) {
                throw new IllegalArgumentException("El identificador ya esta asignado a otro producto");
            }
        }
    }

    private StoreTax tax(UUID id) {
        return sameStore(taxRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Impuesto no encontrado")));
    }

    private Warehouse warehouse(UUID id) {
        return sameStore(warehouseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Almacen no encontrado")));
    }

    private Family family(UUID id) {
        return sameStore(familyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Familia no encontrada")));
    }

    private Family familyForUpdate(UUID id) {
        if (storeRepository == null) {
            return family(id);
        }
        return sameStore(familyRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Familia no encontrada")));
    }

    private Product productForUpdate(UUID storeId, UUID productId) {
        if (storeRepository == null) {
            return product(productId);
        }
        List<Product> products = productRepository.findAllByStoreIdAndIdInForUpdate(
                storeId, List.of(productId));
        if (products.size() != 1) {
            throw new IllegalArgumentException("Producto no encontrado");
        }
        return products.getFirst();
    }

    private List<Product> productsForUpdate(UUID storeId, Set<UUID> productIds) {
        if (storeRepository == null) {
            return productRepository.findAllByStoreIdAndIdIn(storeId, productIds);
        }
        return productRepository.findAllByStoreIdAndIdInForUpdate(storeId, productIds);
    }

    private Subfamily subfamily(UUID id) {
        Subfamily value = subfamilyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Subfamilia no encontrada"));
        family(value.getFamilyId());
        return value;
    }

    private Subfamily subfamilyForUpdate(UUID id) {
        if (storeRepository == null) {
            return subfamily(id);
        }
        // The store mutex is already held; preserve parent-before-child row order.
        Subfamily candidate = subfamilyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Subfamilia no encontrada"));
        Family parent = familyForUpdate(candidate.getFamilyId());
        Subfamily value = subfamilyRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Subfamilia no encontrada"));
        if (!parent.getId().equals(value.getFamilyId())) {
            throw new IllegalStateException("La subfamilia cambio de familia durante el borrado");
        }
        return sameStore(value);
    }

    private static void requireFullyUnreferenced(DeleteImpact impact, String resource) {
        if (impact.blocked() || impact.productCount() != 0) {
            throw new IllegalStateException(
                    "No se puede eliminar la " + resource + " porque aparecieron referencias activas");
        }
    }

    private List<Family> familiesOrdered(UUID storeId) {
        return familyRepository.findByStoreIdOrderByFamilyCodeAscIdAsc(storeId);
    }

    private List<Subfamily> subfamiliesOrdered(UUID familyId) {
        return subfamilyRepository.findByFamilyIdOrderBySubfamilySuffixAscSubfamilyCodeAscIdAsc(familyId);
    }

    private String allocateFamilyCode(UUID storeId) {
        Set<String> used = new HashSet<>();
        familiesOrdered(storeId).stream()
                .map(Family::getFamilyCode)
                .filter(Objects::nonNull)
                .forEach(used::add);
        List<String> reserved = familyRepository.findReservedFamilyCodes(storeId);
        if (reserved != null) {
            used.addAll(reserved);
        }
        return firstAvailableCode(used, 1, 999, "familias");
    }

    private String requestedFamilyCode(UUID storeId, String requestedCode) {
        String code = normalizeNumericCode(requestedCode, "familyCode");
        if ("000".equals(code)) {
            throw new IllegalArgumentException("El codigo 000 esta reservado para GENERAL");
        }
        Set<String> used = new HashSet<>();
        familiesOrdered(storeId).stream().map(Family::getFamilyCode)
                .filter(Objects::nonNull).forEach(used::add);
        List<String> reserved = familyRepository.findReservedFamilyCodes(storeId);
        if (reserved != null) {
            used.addAll(reserved);
        }
        if (used.contains(code)) {
            throw new IllegalArgumentException("Ya existe o fue reservado ese familyCode");
        }
        return code;
    }

    @Transactional
    public void lockStoreForCatalogMutation(UUID storeId) {
        if (storeRepository != null) {
            storeRepository.findByIdForUpdate(storeId)
                    .orElseThrow(() -> new IllegalArgumentException("Tienda no encontrada"));
        }
    }

    private String allocateSubfamilySuffix(UUID familyId) {
        Set<String> used = new HashSet<>();
        subfamiliesOrdered(familyId).stream()
                .map(Subfamily::getSubfamilySuffix)
                .filter(Objects::nonNull)
                .forEach(used::add);
        List<String> reserved = subfamilyRepository.findReservedSubfamilySuffixes(familyId);
        if (reserved != null) {
            used.addAll(reserved);
        }
        return firstAvailableCode(used, 1, 999, "subfamilias");
    }

    private String requestedSubfamilySuffix(UUID familyId, String requestedSuffix) {
        String suffix = normalizeNumericCode(requestedSuffix, "subfamilySuffix");
        if ("000".equals(suffix)) {
            throw new IllegalArgumentException("El sufijo 000 esta reservado");
        }
        Set<String> used = new HashSet<>();
        subfamiliesOrdered(familyId).stream().map(Subfamily::getSubfamilySuffix)
                .filter(Objects::nonNull).forEach(used::add);
        List<String> reserved = subfamilyRepository.findReservedSubfamilySuffixes(familyId);
        if (reserved != null) {
            used.addAll(reserved);
        }
        if (used.contains(suffix)) {
            throw new IllegalArgumentException("Ya existe o fue reservado ese subfamilySuffix");
        }
        return suffix;
    }

    private static String firstAvailableCode(Set<String> used, int first, int last, String resource) {
        for (int value = first; value <= last; value++) {
            String code = String.format(Locale.ROOT, "%03d", value);
            if (!used.contains(code)) {
                return code;
            }
        }
        throw new IllegalStateException("No quedan codigos disponibles para " + resource);
    }

    private DeleteImpact familyDeleteImpact(Family family) {
        List<UUID> targets = new ArrayList<>();
        targets.add(family.getId());
        targets.addAll(subfamiliesOrdered(family.getId()).stream()
                .map(Subfamily::getId)
                .toList());
        return impact(targets, productRepository.countByFamilyId(family.getId()));
    }

    private DeleteImpact subfamilyDeleteImpact(Subfamily subfamily) {
        return impact(List.of(subfamily.getId()), productRepository.countBySubfamilyId(subfamily.getId()));
    }

    private DeleteImpact impact(List<UUID> targets, long products) {
        if (targets.isEmpty()) {
            return new DeleteImpact(0, 0, 0, List.of());
        }
        List<Dependency> dependencies = new ArrayList<>();
        UUID companyId = promotionTargetRepository != null || productPriceRuleRepository != null
                ? organization.currentCompany().getId()
                : null;
        if (promotionTargetRepository != null) {
            promotionTargetRepository.findFamilyOrSubfamilyReferences(companyId, targets).stream()
                    .map(reference -> new Dependency(
                            "PROMOTION", reference.getPromotionId(), reference.getPromotionName(),
                            reference.getType().name(), reference.getTargetId()))
                    .forEach(dependencies::add);
        }
        if (productPriceRuleRepository != null) {
            productPriceRuleRepository.findFamilyOrSubfamilyReferences(
                            companyId, postgresUuidArray(targets)).stream()
                    .map(reference -> new Dependency(
                            "PRICE_RULE", reference.getRuleId(), reference.getRuleName(),
                            null, null))
                    .forEach(dependencies::add);
        }
        Map<String, Dependency> unique = new LinkedHashMap<>();
        dependencies.forEach(value -> unique.putIfAbsent(
                value.sourceType() + ":" + value.id(), value));
        List<Dependency> distinct = List.copyOf(unique.values());
        long promotionCount = distinct.stream()
                .filter(value -> "PROMOTION".equals(value.sourceType())).count();
        long priceRuleCount = distinct.stream()
                .filter(value -> "PRICE_RULE".equals(value.sourceType())).count();
        return new DeleteImpact(products, promotionCount, priceRuleCount, distinct);
    }

    private static String postgresUuidArray(List<UUID> values) {
        return "{" + values.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",")) + "}";
    }

    private static String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code es obligatorio");
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeNumericCode(String code, String field) {
        String normalized = normalizeCode(code);
        if (!normalized.matches("[0-9]{3}")) {
            throw new IllegalArgumentException(field + " debe tener tres digitos");
        }
        return normalized;
    }

    private static String encodeProductCursor(
            FamilyProductPageRepository.FamilyProductPageRow row,
            String sortBy,
            String sortDirection,
            FamilyProductPageRepository.ScopeKind scopeKind,
            UUID scopeId) {
        String value = String.join("\u0000",
                "v2",
                sortBy,
                sortDirection,
                scopeKind.cursorValue(),
                scopeId.toString(),
                row.nullSortValue() ? "1" : "0",
                familyProductCursorType(sortBy),
                row.sortValue() == null ? "" : row.sortValue(),
                row.id().toString());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static ProductCursor decodeProductCursor(
            String cursor,
            String sortBy,
            String sortDirection,
            FamilyProductPageRepository.ScopeKind scopeKind,
            UUID scopeId) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = value.split("\u0000", -1);
            if (parts.length != 9
                    || !"v2".equals(parts[0])
                    || !sortBy.equals(parts[1])
                    || !sortDirection.equals(parts[2])
                    || scopeKind != FamilyProductPageRepository.ScopeKind.fromCursorValue(parts[3])
                    || !scopeId.equals(UUID.fromString(parts[4]))
                    || !familyProductCursorType(sortBy).equals(parts[6])) {
                throw new IllegalArgumentException("cursor no valido");
            }
            boolean nullSortValue = switch (parts[5]) {
                case "0" -> false;
                case "1" -> true;
                default -> throw new IllegalArgumentException("cursor no valido");
            };
            if ((nullSortValue && !parts[7].isEmpty())
                    || (!nullSortValue && parts[7].isEmpty())
                    || (nullSortValue && "name".equals(sortBy))) {
                throw new IllegalArgumentException("cursor no valido");
            }
            if (!nullSortValue && "salePrice".equals(sortBy)) {
                new BigDecimal(parts[7]);
            }
            return new ProductCursor(
                    nullSortValue, nullSortValue ? null : parts[7], UUID.fromString(parts[8]));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("cursor no valido", exception);
        }
    }

    private static String normalizeFamilyProductSort(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) {
            return "name";
        }
        String normalized = sortBy.trim();
        return switch (normalized) {
            case "code", "name", "salePrice" -> normalized;
            default -> throw new IllegalArgumentException(
                    "sortBy debe ser code, name o salePrice");
        };
    }

    private static String normalizeFamilyProductSortDirection(String sortDirection) {
        if (sortDirection == null || sortDirection.isBlank()) {
            return "asc";
        }
        String normalized = sortDirection.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "asc", "desc" -> normalized;
            default -> throw new IllegalArgumentException(
                    "sortDirection debe ser asc o desc");
        };
    }

    private static String familyProductCursorType(String sortBy) {
        return "salePrice".equals(sortBy) ? "decimal" : "text";
    }

    private static String encodeHierarchySearchCursor(String kind, String code, UUID id) {
        String value = kind + "\u0000" + (code == null ? "" : code) + "\u0000" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static HierarchySearchCursor decodeHierarchySearchCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int first = value.indexOf('\u0000');
            int second = value.indexOf('\u0000', first + 1);
            if (first <= 0 || second <= first + 1 || second == value.length() - 1) {
                throw new IllegalArgumentException("cursor no valido");
            }
            String kind = value.substring(0, first);
            if (!"FAMILY".equals(kind) && !"SUBFAMILY".equals(kind)) {
                throw new IllegalArgumentException("cursor no valido");
            }
            return new HierarchySearchCursor(kind, value.substring(first + 1, second),
                    UUID.fromString(value.substring(second + 1)));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("cursor no valido", exception);
        }
    }

    private void auditFamily(String event, String beforeName, String afterName, Family family) {
        if (auditService == null) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("familyId", family.getId().toString());
        details.put("familyCode", family.getFamilyCode());
        details.put("beforeName", beforeName);
        details.put("afterName", afterName);
        details.put("defaultFamily", family.isDefaultFamily());
        auditService.record(event, AuditResult.EXITO, details);
    }

    private void auditSubfamily(String event, String beforeName, String afterName, Subfamily subfamily) {
        if (auditService == null) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("subfamilyId", subfamily.getId().toString());
        details.put("familyId", subfamily.getFamilyId().toString());
        details.put("subfamilyCode", subfamily.getSubfamilyCode());
        details.put("beforeName", beforeName);
        details.put("afterName", afterName);
        auditService.record(event, AuditResult.EXITO, details);
    }

    private record ProductCursor(boolean nullSortValue, String value, UUID id) {
    }

    private record HierarchySearchCursor(String kind, String code, UUID id) {
    }

    public record MoveProductItem(
            @NotNull UUID productId,
            @NotNull Long expectedVersion) {
    }

    public record BulkMoveRequest(
            @NotNull List<MoveProductItem> items,
            UUID familyId,
            UUID subfamilyId) {
    }

    public record BulkMoveResult(
            int requestedCount,
            int updatedCount,
            UUID familyId,
            UUID subfamilyId) {
    }

    public record FamilyResolution(FamilyReference family, SubfamilyReference subfamily) {

        static FamilyResolution from(Family value, Subfamily child) {
            return new FamilyResolution(
                new FamilyReference(value.getId(), value.getFamilyId(), value.getFamilyCode(),
                            value.getName(), value.isDefaultFamily()),
                    child == null ? null : new SubfamilyReference(
                            child.getId(), child.getFamilyId(), child.getSubfamilyId(),
                            child.getSubfamilySuffix(), child.getSubfamilyCode(),
                            child.getName()));
        }
    }

    public record FamilyReference(
            UUID id,
            String familyId,
            String familyCode,
            String name,
            boolean defaultFamily) {
    }

    public record SubfamilyReference(
            UUID id,
            UUID familyId,
            String subfamilyId,
            String subfamilySuffix,
            String subfamilyCode,
            String name) {
    }

    public record Dependency(
            String sourceType,
            UUID id,
            String name,
            String targetType,
            UUID targetId) {
    }

    public record DeleteImpact(
            long productCount,
            long promotionCount,
            long priceRuleCount,
            List<Dependency> dependencies) {

        @JsonProperty("blocked")
        public boolean isBlocked() {
            return promotionCount > 0 || priceRuleCount > 0;
        }

        public boolean blocked() {
            return isBlocked();
        }
    }

    private <T> T sameStore(T value) {
        UUID current = currentStore().getId();
        UUID owner = switch (value) {
            case StoreTax tax -> tax.getStoreId();
            case Warehouse warehouse -> warehouse.getStoreId();
            case Family family -> family.getStoreId();
            case Product product -> product.getStoreId();
            default -> throw new IllegalArgumentException("Tipo de catalogo no soportado");
        };
        if (!Objects.equals(current, owner)) {
            throw new IllegalArgumentException("El recurso no pertenece a la tienda actual");
        }
        return value;
    }

    private Store currentStore() {
        return organization.currentStore();
    }

    private static Product initializeProductForApi(Product product) {
        product.getCode();
        product.getBarcode();
        product.getBarcode2();
        product.getSalePrice();
        product.getMemberPrice();
        product.getWholesalePrice();
        product.getOfferPrice();
        return product;
    }

    @Transactional
    public List<Product> updateProducts(List<BulkProductUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            throw new IllegalArgumentException("La lista de productos esta vacia");
        }
        if (updates.size() > 5_000) {
            throw new IllegalArgumentException("La lista de productos no puede superar 5000 filas");
        }
        Set<UUID> productIds = new HashSet<>();
        for (int index = 0; index < updates.size(); index++) {
            BulkProductUpdate update = updates.get(index);
            if (update == null || update.productId() == null || update.expectedVersion() == null
                    || update.product() == null) {
                throw new IllegalArgumentException("updates[" + index + "] no es valido");
            }
            if (!productIds.add(update.productId())) {
                throw new IllegalArgumentException(
                        "updates contiene el producto duplicado " + update.productId());
            }
        }

        UUID storeId = currentStore().getId();
        lockStoreForCatalogMutation(storeId);
        Map<UUID, Product> currentProducts = new HashMap<>();
        productsForUpdate(storeId, productIds)
                .forEach(product -> currentProducts.put(product.getId(), product));
        if (currentProducts.size() != productIds.size()) {
            throw new IllegalArgumentException("Producto no encontrado");
        }
        for (BulkProductUpdate update : updates) {
            Product current = currentProducts.get(update.productId());
            if (current.getVersion() != update.expectedVersion()) {
                throw staleProductVersion(
                        update.productId(), update.expectedVersion(), current.getVersion());
            }
        }

        List<Product> changed = new ArrayList<>(updates.size());
        for (BulkProductUpdate update : updates) {
            changed.add(updateProductLocked(currentProducts.get(update.productId()), update.product()));
        }
        return List.copyOf(changed);
    }

    private static IllegalStateException staleProductVersion(
            UUID productId, long expectedVersion, long currentVersion) {
        return new IllegalStateException(
                "Conflicto de version en el producto " + productId
                        + ": se esperaba " + expectedVersion
                        + " y tiene version " + currentVersion);
    }

    public record ProductRequest(
            @NotNull UUID familyId,
            UUID subfamilyId,
            @NotNull UUID taxId,
            @NotNull ProductType productType,
            @NotNull DiscountType discountType,
            PriceUseMode priceUseMode,
            @NotBlank String name,
            String description,
            String comments,
            @NotNull BigDecimal purchasePrice,
            boolean taxesIncluded,
            String code,
            String barcode,
            String barcode2,
            @NotNull BigDecimal salePrice,
            BigDecimal memberPrice,
            BigDecimal wholesalePrice,
            BigDecimal offerPrice,
            BigDecimal offerDiscountPercent,
            BigDecimal purchaseDiscountPercent,
            boolean offerActive,
            LocalDate offerFrom,
            LocalDate offerUntil,
            BigDecimal stockMin,
            BigDecimal stockMax,
            BigDecimal packageQuantity,
            Boolean active,
            Boolean requiresSerialNumber) {

        public ProductRequest {
            if (discountType == DiscountType.NONE) {
                priceUseMode = PriceUseMode.NORMAL;
                offerActive = false;
            } else {
                priceUseMode = priceUseMode == null ? priceUseModeFromDiscountType(discountType) : priceUseMode;
                discountType = discountTypeFromPriceUseMode(priceUseMode, discountType);
            }
        }

        public ProductRequest withPrimaryBarcode(String forcedBarcode) {
            return new ProductRequest(
                    familyId, subfamilyId, taxId, productType, discountType,
                    priceUseMode, name, description, comments, purchasePrice,
                    taxesIncluded, code, forcedBarcode, barcode2, salePrice,
                    memberPrice, wholesalePrice, offerPrice,
                    offerDiscountPercent, purchaseDiscountPercent, offerActive,
                    offerFrom, offerUntil, stockMin, stockMax, packageQuantity,
                    active, requiresSerialNumber);
        }

        public ProductRequest(
                UUID familyId,
                UUID subfamilyId,
                UUID taxId,
                ProductType productType,
                DiscountType discountType,
                PriceUseMode priceUseMode,
                String name,
                String description,
                String comments,
                BigDecimal purchasePrice,
                boolean taxesIncluded,
                String code,
                String barcode,
                String barcode2,
                BigDecimal salePrice,
                BigDecimal memberPrice,
                BigDecimal wholesalePrice,
                BigDecimal offerPrice,
                BigDecimal offerDiscountPercent,
                BigDecimal purchaseDiscountPercent,
                boolean offerActive,
                LocalDate offerFrom,
                LocalDate offerUntil) {
            this(familyId, subfamilyId, taxId, productType, discountType, priceUseMode, name, description,
                    comments, purchasePrice, taxesIncluded, code, barcode, barcode2, salePrice, memberPrice,
                    wholesalePrice, offerPrice, offerDiscountPercent, purchaseDiscountPercent, offerActive,
                    offerFrom, offerUntil, null, null, null, null, null);
        }

        public ProductRequest(
                UUID familyId,
                UUID subfamilyId,
                UUID taxId,
                ProductType productType,
                DiscountType discountType,
                PriceUseMode priceUseMode,
                String name,
                String description,
                String comments,
                BigDecimal purchasePrice,
                boolean taxesIncluded,
                String code,
                String barcode,
                String barcode2,
                BigDecimal salePrice,
                BigDecimal memberPrice,
                BigDecimal wholesalePrice,
                BigDecimal offerPrice,
                BigDecimal offerDiscountPercent,
                BigDecimal purchaseDiscountPercent,
                boolean offerActive,
                LocalDate offerFrom,
                LocalDate offerUntil,
                BigDecimal stockMin,
                BigDecimal stockMax) {
            this(familyId, subfamilyId, taxId, productType, discountType, priceUseMode, name, description,
                    comments, purchasePrice, taxesIncluded, code, barcode, barcode2, salePrice, memberPrice,
                    wholesalePrice, offerPrice, offerDiscountPercent, purchaseDiscountPercent, offerActive,
                    offerFrom, offerUntil, stockMin, stockMax, null, null, null);
        }

        public ProductRequest(
                UUID familyId,
                UUID subfamilyId,
                UUID taxId,
                ProductType productType,
                DiscountType discountType,
                PriceUseMode priceUseMode,
                String name,
                String description,
                String comments,
                BigDecimal purchasePrice,
                boolean taxesIncluded,
                String code,
                String barcode,
                String barcode2,
                BigDecimal salePrice,
                BigDecimal memberPrice,
                BigDecimal wholesalePrice,
                BigDecimal offerPrice,
                BigDecimal offerDiscountPercent,
                BigDecimal purchaseDiscountPercent,
                boolean offerActive,
                LocalDate offerFrom,
                LocalDate offerUntil,
                BigDecimal stockMin,
                BigDecimal stockMax,
                BigDecimal packageQuantity) {
            this(familyId, subfamilyId, taxId, productType, discountType, priceUseMode, name, description,
                    comments, purchasePrice, taxesIncluded, code, barcode, barcode2, salePrice, memberPrice,
                    wholesalePrice, offerPrice, offerDiscountPercent, purchaseDiscountPercent, offerActive,
                    offerFrom, offerUntil, stockMin, stockMax, packageQuantity, null, null);
        }

        public ProductRequest(
                UUID familyId, UUID subfamilyId, UUID taxId, ProductType productType,
                DiscountType discountType, PriceUseMode priceUseMode, String name,
                String description, String comments, BigDecimal purchasePrice,
                boolean taxesIncluded, String code, String barcode, String barcode2,
                BigDecimal salePrice, BigDecimal memberPrice, BigDecimal wholesalePrice,
                BigDecimal offerPrice, BigDecimal offerDiscountPercent,
                BigDecimal purchaseDiscountPercent, boolean offerActive, LocalDate offerFrom,
                LocalDate offerUntil, BigDecimal stockMin, BigDecimal stockMax,
                BigDecimal packageQuantity, Boolean active) {
            this(familyId, subfamilyId, taxId, productType, discountType, priceUseMode, name,
                    description, comments, purchasePrice, taxesIncluded, code, barcode, barcode2,
                    salePrice, memberPrice, wholesalePrice, offerPrice, offerDiscountPercent,
                    purchaseDiscountPercent, offerActive, offerFrom, offerUntil, stockMin,
                    stockMax, packageQuantity, active, null);
        }

        public ProductRequest(
                UUID familyId, UUID subfamilyId, UUID taxId, ProductType productType,
                DiscountType discountType, PriceUseMode priceUseMode, String name,
                String description, String comments, BigDecimal purchasePrice,
                boolean taxesIncluded, String code, String barcode, String barcode2,
                BigDecimal salePrice, BigDecimal memberPrice, BigDecimal wholesalePrice,
                BigDecimal offerPrice, BigDecimal offerDiscountPercent,
                BigDecimal purchaseDiscountPercent, boolean offerActive, LocalDate offerFrom,
                LocalDate offerUntil, BigDecimal stockMin, BigDecimal stockMax,
                BigDecimal packageQuantity, boolean active) {
            this(familyId, subfamilyId, taxId, productType, discountType, priceUseMode, name,
                    description, comments, purchasePrice, taxesIncluded, code, barcode, barcode2,
                    salePrice, memberPrice, wholesalePrice, offerPrice, offerDiscountPercent,
                    purchaseDiscountPercent, offerActive, offerFrom, offerUntil, stockMin,
                    stockMax, packageQuantity, active, null);
        }
    }

    public record BulkProductUpdate(
            @NotNull UUID productId,
            @NotNull Long expectedVersion,
            @NotNull @jakarta.validation.Valid ProductRequest product) {
    }

    private record PriceSnapshot(
            BigDecimal purchasePrice,
            BigDecimal salePrice,
            BigDecimal memberPrice,
            BigDecimal wholesalePrice,
            BigDecimal offerPrice) {

        static PriceSnapshot from(Product product) {
            return new PriceSnapshot(
                    product.getPurchasePrice(),
                    product.getSalePrice(),
                    product.getMemberPrice(),
                    product.getWholesalePrice(),
                    product.getOfferPrice());
        }
    }
}
