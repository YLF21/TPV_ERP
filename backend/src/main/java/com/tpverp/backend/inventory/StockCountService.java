package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.ProductType;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockCountService {
    private final StockCountRepository counts;
    private final StockCountLineRepository lines;
    private final StockLevelRepository stocks;
    private final StockMovementRepository movements;
    private final ProductRepository products;
    private final WarehouseRepository warehouses;
    private final CurrentOrganization organization;
    private final StockMovementSyncPublisher syncPublisher;
    private final Clock clock;

    public StockCountService(
            StockCountRepository counts, StockCountLineRepository lines,
            StockLevelRepository stocks, StockMovementRepository movements,
            ProductRepository products, WarehouseRepository warehouses,
            CurrentOrganization organization, StockMovementSyncPublisher syncPublisher, Clock clock) {
        this.counts = counts;
        this.lines = lines;
        this.stocks = stocks;
        this.movements = movements;
        this.products = products;
        this.warehouses = warehouses;
        this.organization = organization;
        this.syncPublisher = syncPublisher;
        this.clock = clock;
    }

    @Transactional
    public StockCountView create(UUID warehouseId, String notes, Authentication authentication) {
        var storeId = organization.currentStore().getId();
        warehouse(warehouseId, storeId);
        if (counts.existsByStoreIdAndWarehouseIdAndStatus(storeId, warehouseId, StockCountStatus.DRAFT)) {
            throw new IllegalStateException("Ya existe un recuento en borrador para este almacen");
        }
        var user = organization.currentUser(authentication);
        try {
            var count = counts.saveAndFlush(new StockCount(
                    storeId, warehouseId, notes, user.getId(), Instant.now(clock)));
            return view(count);
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalStateException("Ya existe un recuento en borrador para este almacen", exception);
        }
    }

    @Transactional(readOnly = true)
    public List<StockCountSummary> list(StockCountStatus status, UUID warehouseId) {
        var storeId = organization.currentStore().getId();
        if (warehouseId != null) warehouse(warehouseId, storeId);
        var values = status == null
                ? counts.findByStoreIdOrderByCreatedAtDesc(storeId)
                : counts.findByStoreIdAndStatusOrderByCreatedAtDesc(storeId, status);
        return values.stream().filter(value -> warehouseId == null || warehouseId.equals(value.getWarehouseId()))
                .map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public StockCountView get(UUID id) { return view(find(id)); }

    @Transactional
    public StockCountView upsertLine(UUID id, UUID productId, BigDecimal countedQuantity) {
        var count = locked(id);
        count.requireDraft();
        var product = product(productId, count.getStoreId());
        var counted = quantity(countedQuantity);
        if (counted.signum() < 0) throw new IllegalArgumentException("La cantidad contada no puede ser negativa");
        validateProductQuantity(product, counted);
        var expected = stocks.findByProductIdAndWarehouseId(productId, count.getWarehouseId())
                .map(StockLevel::getQuantity).orElse(BigDecimal.ZERO).setScale(3);
        var line = lines.findByCountIdAndProductId(id, productId)
                .orElseGet(() -> new StockCountLine(id, productId, expected, counted));
        line.update(expected, counted);
        lines.save(line);
        return view(count);
    }

    @Transactional
    public StockCountView confirm(UUID id, Authentication authentication) {
        var count = locked(id);
        if (count.getStatus() == StockCountStatus.CONFIRMED) return view(count);
        count.requireDraft();
        var countLines = lines.findByCountIdOrderByProductId(id);
        if (countLines.isEmpty()) throw new IllegalStateException("No se puede confirmar un recuento sin lineas");
        if (movements.existsByStockCountId(id)) {
            throw new IllegalStateException("El recuento ya tiene movimientos sin estar confirmado");
        }
        var user = organization.currentUser(authentication);
        for (var line : countLines.stream().sorted(Comparator.comparing(StockCountLine::getProductId)).toList()) {
            var stock = stocks.findByProductIdAndWarehouseIdForUpdate(line.getProductId(), count.getWarehouseId())
                    .orElseGet(() -> new StockLevel(line.getProductId(), count.getWarehouseId()));
            if (stock.getQuantity().compareTo(line.getExpectedQuantity()) != 0) {
                throw new IllegalStateException("El stock cambio durante el recuento; vuelva a registrar la cantidad de " + line.getProductId());
            }
            var difference = line.difference();
            if (difference.signum() != 0) {
                stock.apply(difference);
                stocks.save(stock);
                var movement = movements.save(StockMovement.stockCountAdjustment(
                        line.getProductId(), count.getWarehouseId(), user.getId(), difference,
                        "RECUENTO FISICO " + count.getId(), count.getId(), Instant.now(clock)));
                syncPublisher.enqueue(organization.currentCompany().getId(), count.getStoreId(), movement);
            }
            line.markApplied(difference);
            lines.save(line);
        }
        count.confirm(user.getId(), Instant.now(clock));
        return view(counts.save(count));
    }

    @Transactional
    public StockCountView cancel(UUID id, Authentication authentication) {
        var count = locked(id);
        count.cancel(organization.currentUser(authentication).getId(), Instant.now(clock));
        return view(counts.save(count));
    }

    private StockCount find(UUID id) {
        return counts.findByIdAndStoreId(id, organization.currentStore().getId())
                .orElseThrow(() -> new IllegalArgumentException("Recuento no encontrado"));
    }
    private StockCount locked(UUID id) {
        return counts.findLockedByIdAndStoreId(id, organization.currentStore().getId())
                .orElseThrow(() -> new IllegalArgumentException("Recuento no encontrado"));
    }
    private void warehouse(UUID id, UUID storeId) {
        var warehouse = warehouses.findById(id).orElseThrow(() -> new IllegalArgumentException("Almacen no encontrado"));
        if (!warehouse.getStoreId().equals(storeId)) throw new IllegalArgumentException("El almacen no pertenece a la tienda actual");
        if (!warehouse.isActive()) throw new IllegalStateException("El almacen no esta activo");
    }
    private Product product(UUID id, UUID storeId) {
        var product = products.findById(id).orElseThrow(() -> new IllegalArgumentException("Producto no encontrado"));
        if (!product.getStoreId().equals(storeId)) throw new IllegalArgumentException("El producto no pertenece a la tienda actual");
        if (product.getProductType() == ProductType.SERVICE) throw new IllegalArgumentException("message.product.service_has_no_stock");
        return product;
    }
    private static void validateProductQuantity(Product product, BigDecimal quantity) {
        if (product.getProductType() == ProductType.UNIT && quantity.stripTrailingZeros().scale() > 0)
            throw new IllegalArgumentException("message.product.unit_quantity_must_be_integer");
    }
    private static BigDecimal quantity(BigDecimal value) {
        if (value == null) throw new IllegalArgumentException("La cantidad contada es obligatoria");
        if (value.stripTrailingZeros().scale() > 3) throw new IllegalArgumentException("message.inventory.quantity_scale");
        return value.setScale(3);
    }
    private StockCountView view(StockCount count) {
        var detailLines = lines.findByCountIdOrderByProductId(count.getId()).stream().map(line -> {
            var product = products.findById(line.getProductId()).orElseThrow();
            return new StockCountView.Line(product.getId(), product.getCode(), product.getName(),
                    line.getExpectedQuantity(), line.getCountedQuantity(), line.difference(), line.getAppliedDifference());
        }).toList();
        return new StockCountView(count.getId(), count.getStoreId(), count.getWarehouseId(), count.getStatus(), count.getNotes(),
                count.getCreatedBy(), count.getCreatedAt(), count.getConfirmedBy(), count.getConfirmedAt(),
                count.getCancelledBy(), count.getCancelledAt(), detailLines);
    }
    private StockCountSummary summary(StockCount count) {
        var countLines = lines.findByCountIdOrderByProductId(count.getId());
        var total = countLines.stream().map(StockCountLine::difference).reduce(BigDecimal.ZERO.setScale(3), BigDecimal::add);
        return new StockCountSummary(count.getId(), count.getStoreId(), count.getWarehouseId(), count.getStatus(), count.getNotes(),
                count.getCreatedBy(), count.getCreatedAt(), count.getConfirmedBy(), count.getConfirmedAt(),
                count.getCancelledBy(), count.getCancelledAt(), countLines.size(), total);
    }
}
