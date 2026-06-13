package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.document.ContadorDocumento;
import com.tpverp.backend.document.ContadorDocumentoRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehouseOutputService {

    private final WarehouseOutputRepository outputs;
    private final ContadorDocumentoRepository counters;
    private final StockLevelRepository stockLevels;
    private final StockMovementRepository movements;
    private final CurrentOrganization organization;
    private final ProductRepository products;
    private final WarehouseRepository warehouses;
    private final Clock clock;

    public WarehouseOutputService(
            WarehouseOutputRepository outputs,
            ContadorDocumentoRepository counters,
            StockLevelRepository stockLevels,
            StockMovementRepository movements,
            CurrentOrganization organization,
            ProductRepository products,
            WarehouseRepository warehouses,
            Clock clock) {
        this.outputs = outputs;
        this.counters = counters;
        this.stockLevels = stockLevels;
        this.movements = movements;
        this.organization = organization;
        this.products = products;
        this.warehouses = warehouses;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<WarehouseOutput> list() {
        return outputs.findByStoreIdOrderByFechaDesc(
                organization.currentStore().getId());
    }

    // Crea una salida editable y valida que todos sus productos pertenezcan a la tienda.
    @Transactional
    public WarehouseOutput create(
            WarehouseOutputCommand command, Authentication authentication) {
        var store = organization.currentStore();
        var user = organization.currentUser(authentication);
        validate(command, store.getId());
        var output = new WarehouseOutput(
                store.getId(), command.warehouseId(), command.date(), user.getId());
        output.replace(command.destination(), command.concept(), command.lines());
        return outputs.save(output);
    }

    // Reemplaza por completo un borrador sin alterar su identidad.
    @Transactional
    public WarehouseOutput update(UUID id, WarehouseOutputCommand command) {
        var output = find(id);
        validate(command, output.getStoreId());
        if (!output.getWarehouseId().equals(command.warehouseId())
                || !output.getDate().equals(command.date())) {
            throw new IllegalArgumentException(
                    "El almacén y la fecha no pueden cambiarse en esta operación");
        }
        output.replace(command.destination(), command.concept(), command.lines());
        return outputs.save(output);
    }

    // Elimina únicamente salidas que todavía son borradores.
    @Transactional
    public void delete(UUID id) {
        var output = find(id);
        if (output.getStatus() != WarehouseOutputStatus.BORRADOR) {
            throw new IllegalStateException("Una salida confirmada no se puede eliminar");
        }
        outputs.delete(output);
    }

    // Numera la salida y registra de forma atómica sus movimientos negativos.
    @Transactional
    public WarehouseOutput confirm(UUID id, Authentication authentication) {
        var output = find(id);
        warehouse(output.getWarehouseId(), output.getStoreId());
        var user = organization.currentUser(authentication);
        if (movements.existsByWarehouseOutputId(output.getId())) {
            throw new IllegalStateException("La salida ya tiene movimientos de stock");
        }
        var counter = counters.findByTiendaIdAndTipoAndPeriodo(
                        output.getStoreId(), "SAL", Integer.toString(output.getDate().getYear()))
                .orElseGet(() -> ContadorDocumento.salidaAlmacen(
                        output.getStoreId(), output.getDate()));
        output.confirm(
                counter.siguienteSalidaAlmacen(output.getDate()),
                user.getId(),
                Instant.now(clock));
        counters.save(counter);
        for (var line : output.getLines()) {
            applyLine(output, line, user.getId());
        }
        return outputs.save(output);
    }

    private void applyLine(WarehouseOutput output, WarehouseOutputLine line, UUID userId) {
        var stock = stockLevels.findByProductIdAndWarehouseId(
                        line.getProductId(), output.getWarehouseId())
                .orElseGet(() -> new StockLevel(
                        line.getProductId(), output.getWarehouseId()));
        stock.apply(-line.getQuantity());
        stockLevels.save(stock);
        movements.save(StockMovement.warehouseOutput(
                line.getProductId(),
                output.getWarehouseId(),
                userId,
                output.getId(),
                line.getQuantity(),
                Instant.now(clock)));
    }

    private void validate(WarehouseOutputCommand command, UUID storeId) {
        if (command == null || command.lines() == null || command.lines().isEmpty()) {
            throw new IllegalArgumentException("La salida necesita líneas");
        }
        warehouse(command.warehouseId(), storeId);
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
                .orElseThrow(() -> new IllegalArgumentException("Almacén no encontrado"));
        if (!warehouse.getStoreId().equals(storeId) || !warehouse.isActive()) {
            throw new IllegalArgumentException("Almacén no disponible para la tienda");
        }
        return warehouse;
    }

    private WarehouseOutput find(UUID id) {
        var storeId = organization.currentStore().getId();
        return outputs.findById(id)
                .filter(output -> output.getStoreId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("Salida no encontrada"));
    }
}
