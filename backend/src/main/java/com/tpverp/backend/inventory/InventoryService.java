package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.ProductType;
import com.tpverp.backend.catalog.ProductView;
import com.tpverp.backend.catalog.DiscountType;
import com.tpverp.backend.catalog.PriceUseMode;
import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.shared.api.PagedResult;
import java.time.Clock;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {

    private static final String NEGATIVE_STOCK_ERROR =
            "Stock insuficiente: la configuracion de la tienda no permite stock negativo";
    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT = 500;

    private final CurrentOrganization organization;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final StockLevelRepository stockRepository;
    private final StockSettingsRepository settingsRepository;
    private final StockMovementRepository movementRepository;
    private final StockMovementSyncPublisher syncPublisher;
    private final Clock clock;

    public InventoryService(
            CurrentOrganization organization,
            ProductRepository productRepository,
            WarehouseRepository warehouseRepository,
            StockLevelRepository stockRepository,
            StockSettingsRepository settingsRepository,
            StockMovementRepository movementRepository,
            StockMovementSyncPublisher syncPublisher,
            Clock clock) {
        this.organization = organization;
        this.productRepository = productRepository;
        this.warehouseRepository = warehouseRepository;
        this.stockRepository = stockRepository;
        this.settingsRepository = settingsRepository;
        this.movementRepository = movementRepository;
        this.syncPublisher = syncPublisher;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<StockItem> stock(UUID productId, UUID warehouseId) {
        UUID storeId = currentStore().getId();
        if (productId != null && warehouseId != null) {
            Product product = product(productId, storeId);
            Warehouse warehouse = warehouse(warehouseId, storeId);
            BigDecimal quantity = stockRepository.findByProductIdAndWarehouseId(product.getId(), warehouse.getId())
                    .map(StockLevel::getQuantity)
                    .orElse(BigDecimal.ZERO.setScale(3));
            return List.of(new StockItem(product.getId(), warehouse.getId(), quantity));
        }
        if (productId != null) {
            product(productId, storeId);
            return stockRepository.findByProductId(productId).stream().map(StockItem::from).toList();
        }
        if (warehouseId != null) {
            warehouse(warehouseId, storeId);
            return stockRepository.findByWarehouseId(warehouseId).stream().map(StockItem::from).toList();
        }
        return warehouseRepository.findByStoreIdOrderByNombre(storeId).stream()
                .flatMap(warehouse -> stockRepository.findByWarehouseId(warehouse.getId()).stream())
                .map(StockItem::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<StockMovement> movements(UUID productId) {
        product(productId, currentStore().getId());
        return movementRepository.findByProductIdOrderByCreatedAtDesc(productId);
    }

    @Transactional
    public StockItem adjust(
            UUID productId,
            UUID warehouseId,
            int quantity,
            String reason,
            Authentication authentication) {
        return adjust(productId, warehouseId, BigDecimal.valueOf(quantity), reason, authentication);
    }

    @Transactional
    public StockItem adjust(
            UUID productId,
            UUID warehouseId,
            BigDecimal quantity,
            String reason,
            Authentication authentication) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("El ajuste necesita un motivo");
        }
        UUID storeId = currentStore().getId();
        var delta = scale(quantity);
        validateStockQuantity(product(productId, storeId), delta);
        warehouse(warehouseId, storeId);
        UserAccount user = organization.currentUser(authentication);
        boolean allowNegativeStock = allowsNegativeStock(storeId);
        StockLevel stock = stockLevel(productId, warehouseId, !allowNegativeStock);
        requireAllowedBalance(stock, delta, allowNegativeStock);
        stock.apply(delta);
        stockRepository.save(stock);
        var movement = movementRepository.save(StockMovement.adjustment(
                productId, warehouseId, user.getId(), quantity, reason, Instant.now(clock)));
        enqueueStockMovement(movement);
        return StockItem.from(stock);
    }

    @Transactional
    public TransferResult transfer(
            UUID productId,
            UUID sourceWarehouseId,
            UUID targetWarehouseId,
            int quantity,
            Authentication authentication) {
        return transfer(productId, sourceWarehouseId, targetWarehouseId, BigDecimal.valueOf(quantity), authentication);
    }

    @Transactional
    public TransferResult transfer(
            UUID productId,
            UUID sourceWarehouseId,
            UUID targetWarehouseId,
            BigDecimal quantity,
            Authentication authentication) {
        if (Objects.equals(sourceWarehouseId, targetWarehouseId)) {
            throw new IllegalArgumentException("Los almacenes de origen y destino deben ser distintos");
        }
        UUID storeId = currentStore().getId();
        var transferQuantity = positive(quantity);
        validateStockQuantity(product(productId, storeId), transferQuantity);
        warehouse(sourceWarehouseId, storeId);
        warehouse(targetWarehouseId, storeId);
        UserAccount user = organization.currentUser(authentication);
        boolean allowNegativeStock = allowsNegativeStock(storeId);
        StockLevel source = stockLevel(productId, sourceWarehouseId, !allowNegativeStock);
        requireAllowedBalance(source, transferQuantity.negate(), allowNegativeStock);
        StockLevel target = stockLevel(productId, targetWarehouseId);
        source.apply(transferQuantity.negate());
        target.apply(transferQuantity);
        stockRepository.save(source);
        stockRepository.save(target);

        UUID transferId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        enqueueStockMovement(movementRepository.save(StockMovement.transferOut(
                productId, sourceWarehouseId, user.getId(), quantity, transferId, now)));
        enqueueStockMovement(movementRepository.save(StockMovement.transferIn(
                productId, targetWarehouseId, user.getId(), quantity, transferId, now)));
        return new TransferResult(
                transferId, productId, sourceWarehouseId, targetWarehouseId,
                source.getQuantity(), target.getQuantity());
    }

    private void enqueueStockMovement(StockMovement movement) {
        syncPublisher.enqueue(organization.currentCompany().getId(), currentStore().getId(), movement);
    }

    private StockLevel stockLevel(UUID productId, UUID warehouseId) {
        return stockLevel(productId, warehouseId, false);
    }

    @Transactional(readOnly = true)
    public PagedResult<StockPageItem> stockPage(
            Integer requestedLimit,
            String cursor,
            String search,
            String view,
            String productType,
            String priceUseMode,
            UUID familyId,
            UUID taxId,
            Boolean offerActive,
            boolean includePurchaseFields) {
        UUID storeId = currentStore().getId();
        var limit = normalizedLimit(requestedLimit);
        var parsedCursor = parseCursor(cursor);
        var filters = StockPageFilters.from(search, view, productType, priceUseMode, familyId, taxId, offerActive);
        var products = productRepository.findPageByStoreId(
                storeId,
                filters.search(),
                filters.productType(),
                filters.priceUseMode(),
                filters.discountType(),
                filters.offersOnly(),
                filters.familyId(),
                filters.taxId(),
                filters.offerActive(),
                parsedCursor.name(),
                parsedCursor.id(),
                PageRequest.of(0, limit + 1));
        var hasMore = products.size() > limit;
        var pageProducts = hasMore ? new ArrayList<>(products.subList(0, limit)) : products;
        pageProducts.forEach(InventoryService::initializeProductForApi);
        var productIds = pageProducts.stream().map(Product::getId).toList();
        var stockByProduct = stockRepository.findByProductIdIn(productIds).stream()
                .collect(java.util.stream.Collectors.groupingBy(StockLevel::getProductId));
        var items = pageProducts.stream()
                .map(product -> new StockPageItem(
                        includePurchaseFields ? ProductView.managementView(product) : ProductView.publicView(product),
                        stockByProduct.getOrDefault(product.getId(), List.of()).stream()
                                .map(StockItem::from)
                                .toList()))
                .toList();
        return new PagedResult<>(items, hasMore ? cursorFor(pageProducts.get(pageProducts.size() - 1)) : null, hasMore);
    }

    private static void initializeProductForApi(Product product) {
        product.getCode();
        product.getBarcode();
        product.getBarcode2();
        product.getSalePrice();
        product.getMemberPrice();
        product.getWholesalePrice();
        product.getOfferPrice();
    }

    private StockLevel stockLevel(UUID productId, UUID warehouseId, boolean forUpdate) {
        var stock = forUpdate
                ? stockRepository.findByProductIdAndWarehouseIdForUpdate(productId, warehouseId)
                : stockRepository.findByProductIdAndWarehouseId(productId, warehouseId);
        return stock
                .orElseGet(() -> new StockLevel(productId, warehouseId));
    }

    private boolean allowsNegativeStock(UUID storeId) {
        return settingsRepository.findById(storeId)
                .map(StockSettings::isAllowNegativeStock)
                .orElse(true);
    }

    private static void requireAllowedBalance(
            StockLevel stock, BigDecimal delta, boolean allowNegativeStock) {
        if (!allowNegativeStock && stock.getQuantity().add(delta).signum() < 0) {
            throw new IllegalStateException(NEGATIVE_STOCK_ERROR);
        }
    }

    private Product product(UUID id, UUID storeId) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Producto no encontrado"));
        if (!product.getStoreId().equals(storeId)) {
            throw new IllegalArgumentException("El producto no pertenece a la tienda actual");
        }
        return product;
    }

    private Warehouse warehouse(UUID id, UUID storeId) {
        Warehouse warehouse = warehouseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Almacen no encontrado"));
        if (!warehouse.getStoreId().equals(storeId)) {
            throw new IllegalArgumentException("El almacen no pertenece a la tienda actual");
        }
        if (!warehouse.isActive()) {
            throw new IllegalStateException("El almacen no esta activo");
        }
        return warehouse;
    }

    private Store currentStore() {
        return organization.currentStore();
    }

    private static BigDecimal positive(BigDecimal quantity) {
        var value = scale(quantity);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser positiva");
        }
        return value;
    }

    private static void validateStockQuantity(Product product, BigDecimal quantity) {
        if (product.getProductType() == ProductType.SERVICE) {
            throw new IllegalArgumentException("message.product.service_has_no_stock");
        }
        if (product.getProductType() == ProductType.UNIT
                && scale(quantity).stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("message.product.unit_quantity_must_be_integer");
        }
    }

    private static BigDecimal scale(BigDecimal quantity) {
        if (quantity.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException("message.inventory.quantity_scale");
        }
        return quantity.setScale(3);
    }

    public record StockItem(UUID productId, UUID warehouseId, BigDecimal quantity) {

        static StockItem from(StockLevel stock) {
            return new StockItem(stock.getProductId(), stock.getWarehouseId(), stock.getQuantity());
        }
    }

    public record StockPageItem(ProductView product, List<StockItem> stock) {
    }

    public record TransferResult(
            UUID transferId,
            UUID productId,
            UUID sourceWarehouseId,
            UUID targetWarehouseId,
            BigDecimal sourceQuantity,
            BigDecimal targetQuantity) {
    }

    private static int normalizedLimit(Integer requestedLimit) {
        if (requestedLimit == null || requestedLimit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }

    private static Cursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new Cursor("", new UUID(0L, 0L));
        }
        var separatorIndex = cursor.lastIndexOf('|');
        if (separatorIndex <= 0 || separatorIndex >= cursor.length() - 1) {
            throw new IllegalArgumentException("cursor invalido");
        }
        return new Cursor(cursor.substring(0, separatorIndex), UUID.fromString(cursor.substring(separatorIndex + 1)));
    }

    private static String cursorFor(Product product) {
        return product.getName() + "|" + product.getId();
    }

    private record Cursor(String name, UUID id) {
    }

    private record StockPageFilters(
            String search,
            ProductType productType,
            PriceUseMode priceUseMode,
            DiscountType discountType,
            boolean offersOnly,
            UUID familyId,
            UUID taxId,
            Boolean offerActive) {

        static StockPageFilters from(
                String search,
                String view,
                String productType,
                String priceUseMode,
                UUID familyId,
                UUID taxId,
                Boolean offerActive) {
            var normalizedView = optionalUpper(view);
            var normalizedPriceUseMode = enumValue(PriceUseMode.class, priceUseMode);
            DiscountType discountType = null;
            if ("OFFERS".equals(normalizedView)) {
                return new StockPageFilters(
                        search == null || search.isBlank() ? null : "%" + search.trim().toLowerCase(java.util.Locale.ROOT) + "%",
                        enumValue(ProductType.class, productType),
                        normalizedPriceUseMode,
                        null,
                        true,
                        familyId,
                        taxId,
                        offerActive);
            } else if ("MEMBER_PRICE".equals(normalizedView)) {
                normalizedPriceUseMode = PriceUseMode.MEMBER_PRICE;
            } else if ("NO_DISCOUNT".equals(normalizedView)) {
                discountType = DiscountType.NONE;
            }
            return new StockPageFilters(
                    search == null || search.isBlank() ? null : "%" + search.trim().toLowerCase(java.util.Locale.ROOT) + "%",
                    enumValue(ProductType.class, productType),
                    normalizedPriceUseMode,
                    discountType,
                    false,
                    familyId,
                    taxId,
                    offerActive);
        }
    }

    private static String optionalUpper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
