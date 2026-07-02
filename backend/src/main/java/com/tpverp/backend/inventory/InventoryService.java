package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.ProductType;
import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.UserAccount;
import java.time.Clock;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {

    private final CurrentOrganization organization;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final StockLevelRepository stockRepository;
    private final StockMovementRepository movementRepository;
    private final StockMovementSyncPublisher syncPublisher;
    private final Clock clock;

    public InventoryService(
            CurrentOrganization organization,
            ProductRepository productRepository,
            WarehouseRepository warehouseRepository,
            StockLevelRepository stockRepository,
            StockMovementRepository movementRepository,
            StockMovementSyncPublisher syncPublisher,
            Clock clock) {
        this.organization = organization;
        this.productRepository = productRepository;
        this.warehouseRepository = warehouseRepository;
        this.stockRepository = stockRepository;
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
        validateStockQuantity(product(productId, storeId), quantity);
        warehouse(warehouseId, storeId);
        UserAccount user = organization.currentUser(authentication);
        StockLevel stock = stockRepository.findByProductIdAndWarehouseId(productId, warehouseId)
                .orElseGet(() -> new StockLevel(productId, warehouseId));
        stock.apply(quantity);
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
        validateStockQuantity(product(productId, storeId), quantity);
        warehouse(sourceWarehouseId, storeId);
        warehouse(targetWarehouseId, storeId);
        UserAccount user = organization.currentUser(authentication);
        StockLevel source = stockLevel(productId, sourceWarehouseId);
        StockLevel target = stockLevel(productId, targetWarehouseId);
        source.apply(positive(quantity).negate());
        target.apply(quantity);
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
        return stockRepository.findByProductIdAndWarehouseId(productId, warehouseId)
                .orElseGet(() -> new StockLevel(productId, warehouseId));
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

    public record TransferResult(
            UUID transferId,
            UUID productId,
            UUID sourceWarehouseId,
            UUID targetWarehouseId,
            BigDecimal sourceQuantity,
            BigDecimal targetQuantity) {
    }
}
