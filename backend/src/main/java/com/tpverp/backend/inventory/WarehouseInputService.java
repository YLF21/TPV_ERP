package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.document.DocumentCounter;
import com.tpverp.backend.document.DocumentCounterRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.SupplierRepository;
import com.tpverp.backend.shared.api.PagedResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehouseInputService {

    private static final String NEGATIVE_STOCK_ERROR =
            "Stock insuficiente: la configuracion de la tienda no permite stock negativo";
    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT = 500;

    private final WarehouseInputRepository inputs;
    private final DocumentCounterRepository counters;
    private final StockLevelRepository stockLevels;
    private final StockSettingsRepository settings;
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
            StockSettingsRepository settings,
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
        this.settings = settings;
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

    @Transactional(readOnly = true)
    public PagedResult<WarehouseInputView> listPage(Integer requestedLimit, String cursor) {
        var limit = normalizedLimit(requestedLimit);
        var parsedCursor = parseCursor(cursor);
        var values = inputs.findPageByStoreId(
                organization.currentStore().getId(),
                parsedCursor.date(),
                parsedCursor.id(),
                PageRequest.of(0, limit + 1));
        var hasMore = values.size() > limit;
        var pageValues = hasMore ? new ArrayList<>(values.subList(0, limit)) : values;
        var items = pageValues.stream().map(WarehouseInputView::from).toList();
        return new PagedResult<>(items, hasMore ? cursorFor(pageValues.get(pageValues.size() - 1)) : null, hasMore);
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
        var confirmationStocks = stocksForConfirmation(input);
        var counter = counters.findByTiendaIdAndTipoAndPeriodo(
                        input.getStoreId(), "ENT", Integer.toString(input.getDate().getYear()))
                .orElseGet(() -> DocumentCounter.entradaAlmacen(
                        input.getStoreId(), input.getDate()));
        try {
            input.confirm(nextAvailableNumber(input, counter), user.getId(), Instant.now(clock));
            inputs.saveAndFlush(input);
        } catch (DataIntegrityViolationException exception) {
            throw new WarehouseConfirmationException(
                    "No se pudo confirmar entrada de almacen: conflicto al guardar el documento numerado",
                    exception);
        }
        try {
            counters.saveAndFlush(counter);
        } catch (DataIntegrityViolationException exception) {
            throw new WarehouseConfirmationException(
                    "No se pudo confirmar entrada de almacen: conflicto al actualizar el contador",
                    exception);
        }
        try {
            for (var line : input.getLines()) {
                applyLine(input, line, user.getId(), confirmationStocks.get(line.getProductId()));
            }
            return inputs.saveAndFlush(input);
        } catch (DataIntegrityViolationException exception) {
            throw new WarehouseConfirmationException(
                    "No se pudo confirmar entrada de almacen: conflicto al guardar stock o movimientos",
                    exception);
        }
    }

    private String nextAvailableNumber(WarehouseInput input, DocumentCounter counter) {
        String number;
        do {
            number = counter.siguienteEntradaAlmacen(input.getDate());
        } while (inputs.findByStoreIdAndNumero(input.getStoreId(), number).isPresent());
        return number;
    }

    private void applyLine(
            WarehouseInput input, WarehouseInputLine line, UUID userId, StockLevel stock) {
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

    private Map<UUID, StockLevel> stocksForConfirmation(WarehouseInput input) {
        var deltas = new LinkedHashMap<UUID, BigDecimal>();
        input.getLines().forEach(line -> deltas.merge(
                line.getProductId(), BigDecimal.valueOf(line.getQuantity()), BigDecimal::add));
        boolean allowNegativeStock = settings.findById(input.getStoreId())
                .map(StockSettings::isAllowNegativeStock)
                .orElse(true);
        var result = new LinkedHashMap<UUID, StockLevel>();
        deltas.forEach((productId, delta) -> {
            var found = allowNegativeStock
                    ? stockLevels.findByProductIdAndWarehouseId(
                            productId, input.getWarehouseId())
                    : stockLevels.findByProductIdAndWarehouseIdForUpdate(
                            productId, input.getWarehouseId());
            var stock = found.orElseGet(() -> new StockLevel(
                    productId, input.getWarehouseId()));
            if (!allowNegativeStock && stock.getQuantity().add(delta).signum() < 0) {
                throw new IllegalStateException(NEGATIVE_STOCK_ERROR);
            }
            result.put(productId, stock);
        });
        return result;
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

    private static int normalizedLimit(Integer requestedLimit) {
        if (requestedLimit == null || requestedLimit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }

    private static Cursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new Cursor(null, null);
        }
        var parts = cursor.split("\\|", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("cursor invalido");
        }
        return new Cursor(LocalDate.parse(parts[0]), UUID.fromString(parts[1]));
    }

    private static String cursorFor(WarehouseInput input) {
        return input.getDate() + "|" + input.getId();
    }

    private record Cursor(LocalDate date, UUID id) {
    }
}
