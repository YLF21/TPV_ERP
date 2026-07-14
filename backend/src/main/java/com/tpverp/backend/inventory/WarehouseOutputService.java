package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.document.DocumentCounter;
import com.tpverp.backend.document.DocumentCounterRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehouseOutputService {

    private static final String NEGATIVE_STOCK_ERROR =
            "Stock insuficiente: la configuracion de la tienda no permite stock negativo";

    private final WarehouseOutputRepository outputs;
    private final DocumentCounterRepository counters;
    private final StockLevelRepository stockLevels;
    private final StockSettingsRepository settings;
    private final StockMovementRepository movements;
    private final CurrentOrganization organization;
    private final ProductRepository products;
    private final WarehouseRepository warehouses;
    private final StockMovementSyncPublisher syncPublisher;
    private final Clock clock;

    public WarehouseOutputService(
            WarehouseOutputRepository outputs,
            DocumentCounterRepository counters,
            StockLevelRepository stockLevels,
            StockSettingsRepository settings,
            StockMovementRepository movements,
            CurrentOrganization organization,
            ProductRepository products,
            WarehouseRepository warehouses,
            StockMovementSyncPublisher syncPublisher,
            Clock clock) {
        this.outputs = outputs;
        this.counters = counters;
        this.stockLevels = stockLevels;
        this.settings = settings;
        this.movements = movements;
        this.organization = organization;
        this.products = products;
        this.warehouses = warehouses;
        this.syncPublisher = syncPublisher;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<WarehouseOutput> list() {
        return outputs.findByStoreIdOrderByFechaDesc(
                organization.currentStore().getId());
    }

    // Creates an editable output and validates that every product belongs to the store.
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

    // Fully replaces a draft without changing its identity.
    @Transactional
    public WarehouseOutput update(UUID id, WarehouseOutputCommand command) {
        var output = find(id);
        validate(command, output.getStoreId());
        if (!output.getWarehouseId().equals(command.warehouseId())
                || !output.getDate().equals(command.date())) {
            throw new IllegalArgumentException(
                    "message.warehouse_output.warehouse_and_date_immutable");
        }
        output.replace(command.destination(), command.concept(), command.lines());
        return outputs.save(output);
    }

    // Deletes only outputs that are still drafts.
    @Transactional
    public void delete(UUID id) {
        var output = find(id);
        if (output.getStatus() != WarehouseOutputStatus.BORRADOR) {
            throw new IllegalStateException("Una salida confirmada no se puede eliminar");
        }
        outputs.delete(output);
    }

    // Numbers the output and atomically records its negative movements.
    @Transactional
    public WarehouseOutput confirm(UUID id, Authentication authentication) {
        var output = find(id);
        warehouse(output.getWarehouseId(), output.getStoreId());
        var user = organization.currentUser(authentication);
        if (movements.existsByWarehouseOutputId(output.getId())) {
            throw new IllegalStateException("La salida ya tiene movimientos de stock");
        }
        var confirmationStocks = stocksForConfirmation(output);
        var counter = counters.findByTiendaIdAndTipoAndPeriodo(
                        output.getStoreId(), "SAL", Integer.toString(output.getDate().getYear()))
                .orElseGet(() -> DocumentCounter.salidaAlmacen(
                        output.getStoreId(), output.getDate()));
        try {
            output.confirm(nextAvailableNumber(output, counter), user.getId(), Instant.now(clock));
            outputs.saveAndFlush(output);
        } catch (DataIntegrityViolationException exception) {
            throw new WarehouseConfirmationException(
                    "No se pudo confirmar salida de almacen: conflicto al guardar el documento numerado",
                    exception);
        }
        try {
            counters.saveAndFlush(counter);
        } catch (DataIntegrityViolationException exception) {
            throw new WarehouseConfirmationException(
                    "No se pudo confirmar salida de almacen: conflicto al actualizar el contador",
                    exception);
        }
        try {
            for (var line : output.getLines()) {
                applyLine(output, line, user.getId(), confirmationStocks.get(line.getProductId()));
            }
            return outputs.saveAndFlush(output);
        } catch (DataIntegrityViolationException exception) {
            throw new WarehouseConfirmationException(
                    "No se pudo confirmar salida de almacen: conflicto al guardar stock o movimientos",
                    exception);
        }
    }

    private String nextAvailableNumber(WarehouseOutput output, DocumentCounter counter) {
        String number;
        do {
            number = counter.siguienteSalidaAlmacen(output.getDate());
        } while (outputs.findByStoreIdAndNumero(output.getStoreId(), number).isPresent());
        return number;
    }

    private void applyLine(
            WarehouseOutput output, WarehouseOutputLine line, UUID userId, StockLevel stock) {
        stock.apply(-line.getQuantity());
        stockLevels.save(stock);
        var movement = movements.save(StockMovement.warehouseOutput(
                line.getProductId(),
                output.getWarehouseId(),
                userId,
                output.getId(),
                line.getQuantity(),
                Instant.now(clock)));
        syncPublisher.enqueue(organization.currentCompany().getId(), output.getStoreId(), movement);
    }

    private Map<UUID, StockLevel> stocksForConfirmation(WarehouseOutput output) {
        var deltas = new LinkedHashMap<UUID, BigDecimal>();
        output.getLines().forEach(line -> deltas.merge(
                line.getProductId(), BigDecimal.valueOf(-line.getQuantity()), BigDecimal::add));
        boolean allowNegativeStock = settings.findById(output.getStoreId())
                .map(StockSettings::isAllowNegativeStock)
                .orElse(true);
        var result = new LinkedHashMap<UUID, StockLevel>();
        deltas.forEach((productId, delta) -> {
            var found = allowNegativeStock
                    ? stockLevels.findByProductIdAndWarehouseId(
                            productId, output.getWarehouseId())
                    : stockLevels.findByProductIdAndWarehouseIdForUpdate(
                            productId, output.getWarehouseId());
            var stock = found.orElseGet(() -> new StockLevel(
                    productId, output.getWarehouseId()));
            if (!allowNegativeStock && stock.getQuantity().add(delta).signum() < 0) {
                throw new IllegalStateException(NEGATIVE_STOCK_ERROR);
            }
            result.put(productId, stock);
        });
        return result;
    }

    private void validate(WarehouseOutputCommand command, UUID storeId) {
        if (command == null || command.lines() == null || command.lines().isEmpty()) {
            throw new IllegalArgumentException("message.warehouse_output.lines_required");
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
                .orElseThrow(() -> new IllegalArgumentException("message.warehouse.not_found"));
        if (!warehouse.getStoreId().equals(storeId) || !warehouse.isActive()) {
            throw new IllegalArgumentException("message.warehouse.not_available_for_store");
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
