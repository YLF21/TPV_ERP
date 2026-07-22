package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.ProductType;
import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.StockDocumentGateway;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentLineType;
import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class InventoryDocumentGateway implements StockDocumentGateway {

    private static final Map<CommercialDocumentType, MovementDefinition> MOVEMENTS =
            movementDefinitions();

    private final StockLevelRepository stockLevels;
    private final StockMovementRepository movements;
    private final ProductRepository products;
    private final CurrentOrganization organization;
    private final StockMovementSyncPublisher syncPublisher;
    private final Clock clock;

    public InventoryDocumentGateway(
            StockLevelRepository stockLevels,
            StockMovementRepository movements,
            ProductRepository products,
            CurrentOrganization organization,
            StockMovementSyncPublisher syncPublisher,
            Clock clock) {
        this.stockLevels = stockLevels;
        this.movements = movements;
        this.products = products;
        this.organization = organization;
        this.syncPublisher = syncPublisher;
        this.clock = clock;
    }

    // Aplica una sola vez todos los movimientos de un documento confirmado.
    @Override
    @Transactional
    public boolean confirm(CommercialDocument document) {
        if (movements.existsByDocumentId(document.getId())) {
            return false;
        }
        var definition = MOVEMENTS.get(document.getTipo());
        if (definition == null) {
            return false;
        }
        for (var line : document.getLineas()) {
            if (line.getLineType() != DocumentLineType.PRODUCT) {
                continue;
            }
            if (isService(line.getProductoId())) {
                continue;
            }
            var quantity = document.getTipo() == CommercialDocumentType.RECTIFICATIVA_VENTA
                    ? line.getCantidad().abs()
                    : line.getCantidad().multiply(BigDecimal.valueOf(definition.sign()));
            apply(line.getProductoId(), document.getAlmacenId(), quantity);
            enqueue(document, movements.save(StockMovement.document(
                    line.getProductoId(),
                    document.getAlmacenId(),
                    document.getStockUserId(),
                    document.getId(),
                    definition.type(),
                    quantity,
                    Instant.now(clock))));
        }
        return true;
    }

    // Compensates each original movement once and preserves its historical link.
    @Override
    @Transactional
    public boolean cancel(CommercialDocument document) {
        var applied = false;
        for (var original : movements.findByDocumentIdAndCompensationOfIdIsNull(
                document.getId())) {
            if (movements.existsByCompensationOfId(original.getId())) {
                continue;
            }
            var compensation = StockMovement.compensation(
                    original, document.getStockUserId(), Instant.now(clock));
            apply(
                    compensation.getProductId(),
                    compensation.getWarehouseId(),
                    compensation.getQuantity());
            enqueue(document, movements.save(compensation));
            applied = true;
        }
        return applied;
    }

    private void enqueue(CommercialDocument document, StockMovement movement) {
        syncPublisher.enqueue(organization.currentCompany().getId(), document.getTiendaId(), movement);
    }

    private boolean isService(java.util.UUID productId) {
        return products.findById(productId)
                .map(product -> product.getProductType() == ProductType.SERVICE)
                .orElse(false);
    }

    private void apply(java.util.UUID productId, java.util.UUID warehouseId, BigDecimal quantity) {
        var stock = stockLevels.findByProductIdAndWarehouseId(productId, warehouseId)
                .orElseGet(() -> new StockLevel(productId, warehouseId));
        stock.apply(quantity);
        stockLevels.save(stock);
    }

    private static Map<CommercialDocumentType, MovementDefinition> movementDefinitions() {
        var definitions = new EnumMap<CommercialDocumentType, MovementDefinition>(
                CommercialDocumentType.class);
        definitions.put(
                CommercialDocumentType.ALBARAN_VENTA,
                new MovementDefinition(StockMovementType.ALBARAN_VENTA, -1));
        definitions.put(
                CommercialDocumentType.ALBARAN_COMPRA,
                new MovementDefinition(StockMovementType.ALBARAN_COMPRA, 1));
        definitions.put(
                CommercialDocumentType.TICKET,
                new MovementDefinition(StockMovementType.TICKET, -1));
        definitions.put(
                CommercialDocumentType.FACTURA_VENTA,
                new MovementDefinition(StockMovementType.FACTURA_VENTA, -1));
        definitions.put(
                CommercialDocumentType.FACTURA_COMPRA,
                new MovementDefinition(StockMovementType.FACTURA_COMPRA, 1));
        definitions.put(
                CommercialDocumentType.RECTIFICATIVA_VENTA,
                new MovementDefinition(StockMovementType.FACTURA_VENTA, 1));
        definitions.put(
                CommercialDocumentType.RECTIFICATIVA_COMPRA,
                new MovementDefinition(StockMovementType.FACTURA_COMPRA, -1));
        return Map.copyOf(definitions);
    }

    private record MovementDefinition(StockMovementType type, int sign) {
    }
}
