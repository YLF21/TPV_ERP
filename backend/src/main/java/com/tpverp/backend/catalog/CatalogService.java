package com.tpverp.backend.catalog;

import com.tpverp.backend.inventory.StockLevelRepository;
import com.tpverp.backend.inventory.StockMovementRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {

    private final CurrentOrganization organization;
    private final StoreTaxRepository taxRepository;
    private final WarehouseRepository warehouseRepository;
    private final FamilyRepository familyRepository;
    private final SubfamilyRepository subfamilyRepository;
    private final ProductRepository productRepository;
    private final ProductIdentifierRepository identifierRepository;
    private final ProductPriceHistoryRepository priceHistoryRepository;
    private final StockLevelRepository stockRepository;
    private final StockMovementRepository movementRepository;
    private final Clock clock;

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
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<StoreTax> taxes() {
        return taxRepository.findByStoreIdOrderByPorcentaje(currentStore().getId());
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
        return familyRepository.findByStoreIdOrderByNombre(currentStore().getId());
    }

    @Transactional(readOnly = true)
    public List<Subfamily> subfamilies(UUID familyId) {
        family(familyId);
        return subfamilyRepository.findByFamilyIdOrderByNombre(familyId);
    }

    @Transactional
    public Family createFamily(String name) {
        return familyRepository.save(new Family(currentStore().getId(), name, false));
    }

    @Transactional
    public Family renameFamily(UUID familyId, String name) {
        Family family = family(familyId);
        family.rename(name);
        return family;
    }

    @Transactional
    public void deleteFamily(UUID familyId) {
        Family family = family(familyId);
        family.requireDeletable();
        Family general = familyRepository.findByStoreIdAndPredeterminadaTrue(family.getStoreId())
                .orElseThrow(() -> new IllegalStateException("La familia GENERAL no esta inicializada"));
        productRepository.findByFamilyId(familyId).forEach(product -> product.update(
                general.getId(), null, product.getTaxId(), product.getProductType(),
                product.getDiscountType(), product.getName(), product.getDescription(),
                product.getComments(), product.getPurchasePrice(), product.isTaxesIncluded()));
        familyRepository.delete(family);
    }

    @Transactional
    public Subfamily createSubfamily(UUID familyId, String name) {
        family(familyId);
        return subfamilyRepository.save(new Subfamily(familyId, name));
    }

    @Transactional
    public Subfamily renameSubfamily(UUID subfamilyId, String name) {
        Subfamily subfamily = subfamily(subfamilyId);
        subfamily.rename(name);
        return subfamily;
    }

    @Transactional
    public void deleteSubfamily(UUID subfamilyId) {
        Subfamily subfamily = subfamily(subfamilyId);
        productRepository.findByStoreIdOrderByNombre(currentStore().getId()).stream()
                .filter(product -> subfamilyId.equals(product.getSubfamilyId()))
                .forEach(product -> product.update(
                        product.getFamilyId(), null, product.getTaxId(), product.getProductType(),
                        product.getDiscountType(), product.getName(), product.getDescription(),
                        product.getComments(), product.getPurchasePrice(), product.isTaxesIncluded()));
        subfamilyRepository.delete(subfamily);
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
    public Product updateProduct(UUID productId, ProductRequest request) {
        Product product = product(productId);
        validateProductRequest(productId, product.getStoreId(), request);
        PriceSnapshot before = PriceSnapshot.from(product);
        product.update(
                request.familyId(), request.subfamilyId(), request.taxId(), request.productType(),
                request.discountType(), request.name(), request.description(), request.comments(),
                request.purchasePrice(), request.taxesIncluded());
        applyProductData(product, request);
        recordChangedPrices(product, before);
        return product;
    }

    @Transactional(readOnly = true)
    public void validateProductUpdate(UUID productId, ProductRequest request) {
        Product product = product(productId);
        validateProductRequest(productId, product.getStoreId(), request);
    }

    @Transactional
    public Product createOrUpdateFromImport(ProductRequest request, UUID existingProductId) {
        return existingProductId == null
                ? createProduct(request)
                : updateProduct(existingProductId, request);
    }

    @Transactional(readOnly = true)
    public List<ProductPriceHistory> priceHistory(UUID productId) {
        Product product = product(productId);
        return priceHistoryRepository.findByProductIdOrderByUpdatedAtDesc(product.getId());
    }

    @Transactional
    public void deleteProduct(UUID productId) {
        Product product = product(productId);
        if (stockRepository.existsByProductId(productId) || movementRepository.existsByProductId(productId)) {
            throw new IllegalStateException("No se puede eliminar un producto con historial");
        }
        productRepository.delete(product);
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
        product.configureOffer(offerActive(request), request.offerFrom(), request.offerUntil());
    }

    private void validateProductRequest(UUID productId, UUID storeId, ProductRequest request) {
        Objects.requireNonNull(request, "product");
        validateReferences(request, storeId);
        validateRequiredProductIdentifier(request);
        validateIdentifiers(storeId, productId, request.code(), request.barcode(), request.barcode2());
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
        var currentValues = new java.util.HashSet<String>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            if (!currentValues.add(normalized)) {
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

    private Subfamily subfamily(UUID id) {
        Subfamily value = subfamilyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Subfamilia no encontrada"));
        family(value.getFamilyId());
        return value;
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
        Map<UUID, Product> currentProducts = new HashMap<>();
        productRepository.findAllByStoreIdAndIdIn(storeId, productIds)
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
            changed.add(updateProduct(update.productId(), update.product()));
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
            LocalDate offerUntil) {

        public ProductRequest {
            priceUseMode = priceUseMode == null ? priceUseModeFromDiscountType(discountType) : priceUseMode;
            discountType = discountTypeFromPriceUseMode(priceUseMode, discountType);
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
