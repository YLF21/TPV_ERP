package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.document.DocumentCounter;
import com.tpverp.backend.document.DocumentCounterRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.SupplierRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehouseInputService {

    private final WarehouseInputRepository inputs;
    private final DocumentCounterRepository counters;
    private final StockLevelRepository stockLevels;
    private final StockMovementRepository movements;
    private final CurrentOrganization organization;
    private final ProductRepository products;
    private final WarehouseRepository warehouses;
    private final SupplierRepository suppliers;
    private final StockMovementSyncPublisher syncPublisher;
    private final Clock clock;

    public WarehouseInputService(
            WarehouseInputRepository inputs,
            DocumentCounterRepository counters,
            StockLevelRepository stockLevels,
            StockMovementRepository movements,
            CurrentOrganization organization,
            ProductRepository products,
            WarehouseRepository warehouses,
            SupplierRepository suppliers,
            StockMovementSyncPublisher syncPublisher,
            Clock clock) {
        this.inputs = inputs;
        this.counters = counters;
        this.stockLevels = stockLevels;
        this.movements = movements;
        this.organization = organization;
        this.products = products;
        this.warehouses = warehouses;
        this.suppliers = suppliers;
        this.syncPublisher = syncPublisher;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<WarehouseInput> list() {
        return inputs.findByStoreIdOrderByFechaDesc(organization.currentStore().getId());
    }

    @Transactional
    public WarehouseInput create(WarehouseInputCommand command, Authentication authentication) {
        var store = organization.currentStore();
        var user = organization.currentUser(authentication);
        validate(command, store.getId());
        var input = new WarehouseInput(
                store.getId(), command.warehouseId(), command.date(), user.getId());
        input.replace(command.supplierId(), command.origin(), command.concept(), command.lines());
        return inputs.save(input);
    }

    @Transactional
    public WarehouseInput update(UUID id, WarehouseInputCommand command) {
        var input = find(id);
        validate(command, input.getStoreId());
        if (!input.getWarehouseId().equals(command.warehouseId())
                || !input.getDate().equals(command.date())) {
            throw new IllegalArgumentException(
                    "message.warehouse_input.warehouse_and_date_immutable");
        }
        input.replace(command.supplierId(), command.origin(), command.concept(), command.lines());
        return inputs.save(input);
    }

    @Transactional
    public void delete(UUID id) {
        var input = find(id);
        if (input.getStatus() != WarehouseInputStatus.BORRADOR) {
            throw new IllegalStateException("Una entrada confirmada no se puede eliminar");
        }
        inputs.delete(input);
    }

    @Transactional
    public WarehouseInput confirm(UUID id, Authentication authentication) {
        var input = find(id);
        warehouse(input.getWarehouseId(), input.getStoreId());
        var user = organization.currentUser(authentication);
        if (movements.existsByWarehouseInputId(input.getId())) {
            throw new IllegalStateException("La entrada ya tiene movimientos de stock");
        }
        var counter = counters.findByTiendaIdAndTipoAndPeriodo(
                        input.getStoreId(), "ENT", Integer.toString(input.getDate().getYear()))
                .orElseGet(() -> DocumentCounter.entradaAlmacen(
                        input.getStoreId(), input.getDate()));
        input.confirm(
                counter.siguienteEntradaAlmacen(input.getDate()),
                user.getId(),
                Instant.now(clock));
        counters.save(counter);
        for (var line : input.getLines()) {
            applyLine(input, line, user.getId());
        }
        return inputs.save(input);
    }

    private void applyLine(WarehouseInput input, WarehouseInputLine line, UUID userId) {
        var stock = stockLevels.findByProductIdAndWarehouseId(
                        line.getProductId(), input.getWarehouseId())
                .orElseGet(() -> new StockLevel(line.getProductId(), input.getWarehouseId()));
        stock.apply(line.getQuantity());
        stockLevels.save(stock);
        var movement = movements.save(StockMovement.warehouseInput(
                line.getProductId(),
                input.getWarehouseId(),
                userId,
                input.getId(),
                line.getQuantity(),
                Instant.now(clock)));
        syncPublisher.enqueue(organization.currentCompany().getId(), input.getStoreId(), movement);
    }

    private void validate(WarehouseInputCommand command, UUID storeId) {
        if (command == null || command.lines() == null || command.lines().isEmpty()) {
            throw new IllegalArgumentException("message.warehouse_input.lines_required");
        }
        warehouse(command.warehouseId(), storeId);
        if (command.supplierId() != null) {
            suppliers.findByIdAndCompanyId(command.supplierId(), organization.currentCompany().getId())
                    .orElseThrow(() -> new IllegalArgumentException("Proveedor no encontrado"));
        }
        command.lines().forEach(line -> product(line.productId(), storeId));
    }

    private Product product(UUID id, UUID storeId) {
        var product = products.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Producto no encontrado"));
        if (!product.getStoreId().equals(storeId)) {
            throw new IllegalArgumentException("El producto no pertenece a la tienda");
        }
        return product;
    }

    private Warehouse warehouse(UUID id, UUID storeId) {
        var warehouse = warehouses.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("message.warehouse.not_found"));
        if (!warehouse.getStoreId().equals(storeId) || !warehouse.isActive()) {
            throw new IllegalArgumentException("message.warehouse.not_available_for_store");
        }
        return warehouse;
    }

    private WarehouseInput find(UUID id) {
        var storeId = organization.currentStore().getId();
        return inputs.findById(id)
                .filter(input -> input.getStoreId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("Entrada no encontrada"));
    }
}
