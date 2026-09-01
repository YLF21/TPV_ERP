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
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final StockPageOrderRepository stockPageOrderRepository;
    private final WarehouseRepository warehouseRepository;
    private final StockLevelRepository stockRepository;
    private final StockSettingsRepository settingsRepository;
    private final StockMovementRepository movementRepository;
    private final StockMovementSyncPublisher syncPublisher;
    private final Clock clock;

    public InventoryService(
            CurrentOrganization organization,
            ProductRepository productRepository,
            StockPageOrderRepository stockPageOrderRepository,
            WarehouseRepository warehouseRepository,
            StockLevelRepository stockRepository,
            StockSettingsRepository settingsRepository,
            StockMovementRepository movementRepository,
            StockMovementSyncPublisher syncPublisher,
            Clock clock) {
        this.organization = organization;
        this.productRepository = productRepository;
        this.stockPageOrderRepository = stockPageOrderRepository;
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

    @Transactional
    public BatchTransferResult transferBatch(
            List<TransferCommand> commands,
            Authentication authentication) {
        if (commands == null || commands.isEmpty()) {
            throw new IllegalArgumentException("El lote de transferencias no puede estar vacio");
        }
        if (commands.size() > 100) {
            throw new IllegalArgumentException("El lote no puede superar 100 transferencias");
        }

        UUID storeId = currentStore().getId();
        boolean allowNegativeStock = allowsNegativeStock(storeId);
        Map<UUID, Product> products = new HashMap<>();
        Map<UUID, Warehouse> warehouses = new HashMap<>();
        List<PreparedTransfer> prepared = new ArrayList<>(commands.size());
        Map<StockKey, BigDecimal> deltas = new HashMap<>();

        for (TransferCommand command : commands) {
            Objects.requireNonNull(command, "transferencia");
            UUID productId = Objects.requireNonNull(command.productId(), "productId");
            UUID sourceWarehouseId = Objects.requireNonNull(command.sourceWarehouseId(), "sourceWarehouseId");
            UUID targetWarehouseId = Objects.requireNonNull(command.targetWarehouseId(), "targetWarehouseId");
            if (sourceWarehouseId.equals(targetWarehouseId)) {
                throw new IllegalArgumentException("Los almacenes de origen y destino deben ser distintos");
            }
            BigDecimal transferQuantity = positive(command.quantity());
            Product transferProduct = products.computeIfAbsent(productId, id -> product(id, storeId));
            validateStockQuantity(transferProduct, transferQuantity);
            warehouses.computeIfAbsent(sourceWarehouseId, id -> warehouse(id, storeId));
            warehouses.computeIfAbsent(targetWarehouseId, id -> warehouse(id, storeId));
            prepared.add(new PreparedTransfer(command, transferQuantity));
            deltas.merge(new StockKey(productId, sourceWarehouseId), transferQuantity.negate(), BigDecimal::add);
            deltas.merge(new StockKey(productId, targetWarehouseId), transferQuantity, BigDecimal::add);
        }

        Map<StockKey, StockLevel> stocks = new LinkedHashMap<>();
        deltas.keySet().stream()
                .sorted(Comparator.comparing((StockKey key) -> key.productId().toString())
                        .thenComparing(key -> key.warehouseId().toString()))
                .forEach(key -> stocks.put(key, stockLevel(key.productId(), key.warehouseId(), true)));
        if (!allowNegativeStock) {
            deltas.forEach((key, delta) -> requireAllowedBalance(stocks.get(key), delta, false));
        }

        prepared.forEach(item -> {
            TransferCommand command = item.command();
            stocks.get(new StockKey(command.productId(), command.sourceWarehouseId()))
                    .apply(item.quantity().negate());
            stocks.get(new StockKey(command.productId(), command.targetWarehouseId()))
                    .apply(item.quantity());
        });
        stockRepository.saveAll(stocks.values());

        UUID batchId = UUID.randomUUID();
        UserAccount user = organization.currentUser(authentication);
        Instant now = Instant.now(clock);
        List<TransferResult> results = new ArrayList<>(prepared.size());
        for (PreparedTransfer item : prepared) {
            TransferCommand command = item.command();
            UUID transferId = UUID.randomUUID();
            enqueueStockMovement(movementRepository.save(StockMovement.transferOut(
                    command.productId(), command.sourceWarehouseId(), user.getId(), item.quantity(), transferId, now)));
            enqueueStockMovement(movementRepository.save(StockMovement.transferIn(
                    command.productId(), command.targetWarehouseId(), user.getId(), item.quantity(), transferId, now)));
            results.add(new TransferResult(
                    transferId,
                    command.productId(),
                    command.sourceWarehouseId(),
                    command.targetWarehouseId(),
                    stocks.get(new StockKey(command.productId(), command.sourceWarehouseId())).getQuantity(),
                    stocks.get(new StockKey(command.productId(), command.targetWarehouseId())).getQuantity()));
        }
        return new BatchTransferResult(batchId, List.copyOf(results));
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
            UUID warehouseId,
            String sortBy,
            String sortDirection,
            boolean includePurchaseFields) {
        return stockPage(
                requestedLimit, cursor, search, view, productType, priceUseMode,
                familyId, taxId, offerActive, null, null, warehouseId,
                sortBy, sortDirection, includePurchaseFields);
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
            String stockStatus,
            UUID supplierId,
            UUID warehouseId,
            String sortBy,
            String sortDirection,
            boolean includePurchaseFields) {
        UUID storeId = currentStore().getId();
        var limit = normalizedLimit(requestedLimit);
        var filters = StockPageFilters.from(
                search, view, productType, priceUseMode, familyId, taxId, offerActive,
                stockStatus, supplierId);
        var normalizedSortBy = normalizedStockSort(sortBy, includePurchaseFields);
        var normalizedDirection = normalizedSortDirection(sortDirection);
        var parsedCursor = parseSortCursor(cursor, normalizedSortBy, normalizedDirection);
        var orderedIds = filters.stockStatus() == null && filters.supplierId() == null
                ? stockPageOrderRepository.findProductIds(
                        storeId, filters.search(), filters.productType(), filters.priceUseMode(),
                        filters.discountType(), filters.offersOnly(), filters.familyId(), filters.taxId(),
                        filters.offerActive(), warehouseId, normalizedSortBy, normalizedDirection,
                        parsedCursor.productId(), limit + 1)
                : stockPageOrderRepository.findProductIds(
                        storeId, filters.search(), filters.productType(), filters.priceUseMode(),
                        filters.discountType(), filters.offersOnly(), filters.familyId(), filters.taxId(),
                        filters.offerActive(), filters.stockStatus(), filters.supplierId(), warehouseId,
                        normalizedSortBy, normalizedDirection, parsedCursor.productId(), limit + 1);
        boolean hasMore = orderedIds.size() > limit;
        var pageIds = hasMore ? orderedIds.subList(0, limit) : orderedIds;
        var productsById = productRepository.findAllByStoreIdAndIdIn(storeId, pageIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        Product::getId,
                        product -> product,
                        (left, right) -> left,
                        LinkedHashMap::new));
        var pageProducts = pageIds.stream()
                .map(productsById::get)
                .filter(Objects::nonNull)
                .toList();
        pageProducts.forEach(InventoryService::initializeProductForApi);
        var stockByProduct = stockRepository.findByProductIdIn(pageIds).stream()
                .collect(java.util.stream.Collectors.groupingBy(StockLevel::getProductId));
        var items = pageProducts.stream()
                .map(product -> new StockPageItem(
                        includePurchaseFields
                                ? ProductView.managementView(product)
                                : ProductView.publicView(product),
                        stockByProduct.getOrDefault(product.getId(), List.of()).stream()
                                .map(StockItem::from)
                                .toList()))
                .toList();
        String nextCursor = hasMore && !pageIds.isEmpty()
                ? sortCursorFor(pageIds.getLast(), normalizedSortBy, normalizedDirection)
                : null;
        return new PagedResult<>(items, nextCursor, hasMore);
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

    public record TransferCommand(
            UUID productId,
            UUID sourceWarehouseId,
            UUID targetWarehouseId,
            BigDecimal quantity) {
    }

    public record BatchTransferResult(UUID batchId, List<TransferResult> transfers) {
    }

    private record PreparedTransfer(TransferCommand command, BigDecimal quantity) {
    }

    private record StockKey(UUID productId, UUID warehouseId) {
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

    private static String normalizedStockSort(String sortBy, boolean includePurchaseFields) {
        if (sortBy == null || sortBy.isBlank()) {
            throw new IllegalArgumentException("Columna de ordenacion de stock obligatoria");
        }
        if ("purchasePrice".equals(sortBy) && !includePurchaseFields) {
            throw new IllegalArgumentException("No dispone de permiso para ordenar por precio de compra");
        }
        StockPageOrderRepository.sortExpression(sortBy);
        return sortBy;
    }

    private static String normalizedSortDirection(String direction) {
        if (direction == null || direction.isBlank() || "asc".equalsIgnoreCase(direction)) {
            return "asc";
        }
        if ("desc".equalsIgnoreCase(direction)) {
            return "desc";
        }
        throw new IllegalArgumentException("Direccion de ordenacion no valida");
    }

    private static SortCursor parseSortCursor(
            String cursor,
            String sortBy,
            String sortDirection) {
        if (cursor == null || cursor.isBlank()) {
            return new SortCursor(null, sortBy, sortDirection);
        }
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(cursor),
                    java.nio.charset.StandardCharsets.UTF_8);
            String[] values = decoded.split("\\|", -1);
            if (values.length != 4
                    || !"v1".equals(values[0])
                    || !sortBy.equals(values[1])
                    || !sortDirection.equals(values[2])) {
                throw new IllegalArgumentException("Cursor de stock incompatible con la ordenacion");
            }
            return new SortCursor(UUID.fromString(values[3]), sortBy, sortDirection);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Cursor de stock no valido", exception);
        }
    }

    private static String sortCursorFor(
            UUID productId,
            String sortBy,
            String sortDirection) {
        String value = "v1|" + sortBy + "|" + sortDirection + "|" + productId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private record Cursor(String name, UUID id) {
    }

    private record SortCursor(UUID productId, String sortBy, String sortDirection) {
    }

    private record StockPageFilters(
            String search,
            ProductType productType,
            PriceUseMode priceUseMode,
            DiscountType discountType,
            boolean offersOnly,
            UUID familyId,
            UUID taxId,
            Boolean offerActive,
            String stockStatus,
            UUID supplierId) {

        static StockPageFilters from(
                String search,
                String view,
                String productType,
                String priceUseMode,
                UUID familyId,
                UUID taxId,
                Boolean offerActive) {
            return from(search, view, productType, priceUseMode, familyId, taxId, offerActive, null, null);
        }

        static StockPageFilters from(
                String search,
                String view,
                String productType,
                String priceUseMode,
                UUID familyId,
                UUID taxId,
                Boolean offerActive,
                String stockStatus,
                UUID supplierId) {
            var normalizedView = optionalUpper(view);
            var normalizedPriceUseMode = enumValue(PriceUseMode.class, priceUseMode);
            var normalizedStockStatus = normalizedStockStatus(stockStatus);
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
                        offerActive,
                        normalizedStockStatus,
                        supplierId);
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
                    offerActive,
                    normalizedStockStatus,
                    supplierId);
        }
    }

    private static String normalizedStockStatus(String value) {
        var normalized = optionalUpper(value);
        if (normalized == null || java.util.Set.of("OK", "LOW", "EMPTY", "INACTIVE").contains(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("Estado de stock no valido");
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
