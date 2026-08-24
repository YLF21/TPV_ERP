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
import java.util.function.Function;
import java.util.stream.Collectors;
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
        return listPage(requestedLimit, cursor, null);
    }

    @Transactional(readOnly = true)
    public PagedResult<WarehouseInputView> listPage(
            Integer requestedLimit,
            String cursor,
            WarehouseInputDocumentType type) {
        var limit = normalizedLimit(requestedLimit);
        var parsedCursor = parseCursor(cursor);
        var values = inputs.findPageByStoreIdAndType(
                organization.currentStore().getId(),
                type,
                parsedCursor.date(),
                parsedCursor.id(),
                PageRequest.of(0, limit + 1));
        var hasMore = values.size() > limit;
        var pageValues = hasMore ? new ArrayList<>(values.subList(0, limit)) : values;
        var productIds = pageValues.stream()
                .flatMap(input -> input.getLines().stream())
                .map(WarehouseInputLine::getProductId)
                .distinct()
                .toList();
        var productsById = products.findAllByStoreIdAndIdIn(organization.currentStore().getId(), productIds)
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        var items = pageValues.stream().map(input -> WarehouseInputView.from(input, productsById)).toList();
        return new PagedResult<>(items, hasMore ? cursorFor(pageValues.get(pageValues.size() - 1)) : null, hasMore);
    }

    @Transactional(readOnly = true)
    public WarehouseInputView view(UUID id) {
        var input = find(id);
        var productIds = input.getLines().stream().map(WarehouseInputLine::getProductId).distinct().toList();
        var productsById = products.findAllByStoreIdAndIdIn(input.getStoreId(), productIds)
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        return WarehouseInputView.from(input, productsById);
    }

    @Transactional
    public WarehouseInput create(WarehouseInputCommand command, Authentication authentication) {
        var store = organization.currentStore();
        var user = organization.currentUser(authentication);
        validate(command, store.getId());
        var input = new WarehouseInput(
                store.getId(), command.warehouseId(), command.date(), user.getId(), command.documentType());
        input.replace(
                command.supplierId(),
                command.origin(),
                command.externalNumber(),
                command.concept(),
                command.priceSource(),
                command.globalDiscount(),
                command.sourceDeliveryNoteIds(),
                valuedLines(command, store.getId()),
                command.excelImport());
        return inputs.save(input);
    }

    @Transactional
    public WarehouseInput update(UUID id, WarehouseInputCommand command) {
        var input = find(id);
        validate(command, input.getStoreId());
        if (!input.getWarehouseId().equals(command.warehouseId())
                || input.getDocumentType() != command.documentType()
                || !input.getDate().equals(command.date())) {
            throw new IllegalArgumentException(
                    "message.warehouse_input.warehouse_and_date_immutable");
        }
        input.replace(
                command.supplierId(),
                command.origin(),
                command.externalNumber(),
                command.concept(),
                command.priceSource(),
                command.globalDiscount(),
                command.sourceDeliveryNoteIds(),
                valuedLines(command, input.getStoreId()),
                command.excelImport());
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
        validateSourceDeliveryNotes(input);
        var createsStock = input.createsStockMovement();
        if (createsStock && movements.existsByWarehouseInputId(input.getId())) {
            throw new IllegalStateException("La entrada ya tiene movimientos de stock");
        }
        var confirmationStocks = createsStock ? stocksForConfirmation(input) : Map.<UUID, StockLevel>of();
        var counterPrefix = input.getDocumentType().prefix();
        var counter = counters.findByTiendaIdAndTipoAndPeriodo(
                        input.getStoreId(), counterPrefix, Integer.toString(input.getDate().getYear()))
                .orElseGet(() -> DocumentCounter.entradaAlmacen(
                        input.getStoreId(), input.getDate(), counterPrefix));
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
            if (createsStock) {
                for (var line : input.getLines()) {
                    applyLine(input, line, user.getId(), confirmationStocks.get(line.getProductId()));
                }
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
            number = counter.siguienteEntradaAlmacen(input.getDate(), input.getDocumentType().prefix());
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
                input.getDocumentType().movementType(),
                Instant.now(clock)));
        syncPublisher.enqueue(organization.currentCompany().getId(), input.getStoreId(), movement);
    }

    private Map<UUID, StockLevel> stocksForConfirmation(WarehouseInput input) {
        var deltas = new LinkedHashMap<UUID, BigDecimal>();
        input.getLines().forEach(line -> deltas.merge(
                line.getProductId(), line.getQuantity(), BigDecimal::add));
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

    private Map<UUID, BigDecimal> purchasePricesForConfirmation(WarehouseInput input) {
        var result = new LinkedHashMap<UUID, BigDecimal>();
        input.getLines().forEach(line -> result.computeIfAbsent(
                line.getProductId(),
                productId -> product(productId, input.getStoreId()).getPurchasePrice()));
        return result;
    }

    private void validate(WarehouseInputCommand command, UUID storeId) {
        if (command == null || command.lines() == null || command.lines().isEmpty()) {
            throw new IllegalArgumentException("message.warehouse_input.lines_required");
        }
        warehouse(command.warehouseId(), storeId);
        if (command.documentType() == WarehouseInputDocumentType.FACTURA_ENTRADA
                && command.supplierId() == null) {
            throw new IllegalArgumentException("La factura de entrada necesita proveedor");
        }
        if (command.documentType() != WarehouseInputDocumentType.FACTURA_ENTRADA
                && !command.sourceDeliveryNoteIds().isEmpty()) {
            throw new IllegalArgumentException("Solo una factura de entrada puede vincular albaranes");
        }
        if (command.supplierId() != null) {
            var supplier = suppliers.findByIdAndCompanyId(
                            command.supplierId(), organization.currentCompany().getId())
                    .orElseThrow(() -> new IllegalArgumentException("Proveedor no encontrado"));
            if (!supplier.isActive()) {
                throw new IllegalArgumentException("El proveedor esta inactivo");
            }
        }
        command.lines().forEach(line -> {
            if (line.priceOverridden() && line.unitPrice() == null) {
                throw new IllegalArgumentException("Una linea con precio personalizado necesita importe");
            }
            product(line.productId(), storeId);
        });
    }

    private List<WarehouseInputLineCommand> valuedLines(WarehouseInputCommand command, UUID storeId) {
        return command.lines().stream().map(line -> {
            var product = product(line.productId(), storeId);
            return line.valued(command.priceSource().price(product), product.getName());
        }).toList();
    }

    private void validateSourceDeliveryNotes(WarehouseInput invoice) {
        var sourceIds = invoice.getSourceDeliveryNoteIds();
        if (sourceIds.isEmpty()) {
            return;
        }
        if (invoice.getDocumentType() != WarehouseInputDocumentType.FACTURA_ENTRADA) {
            throw new IllegalStateException("Solo una factura de entrada puede vincular albaranes");
        }
        var expected = new LinkedHashMap<UUID, BigDecimal>();
        for (var sourceId : sourceIds) {
            var source = inputs.findByIdAndStoreId(sourceId, invoice.getStoreId())
                    .orElseThrow(() -> new IllegalStateException("Albaran de entrada no encontrado"));
            if (source.getDocumentType() != WarehouseInputDocumentType.ALBARAN_ENTRADA
                    || source.getStatus() != WarehouseInputStatus.CONFIRMADA) {
                throw new IllegalStateException("La factura solo puede vincular albaranes confirmados");
            }
            if (!source.getWarehouseId().equals(invoice.getWarehouseId())) {
                throw new IllegalStateException("Los albaranes vinculados deben pertenecer al mismo almacen");
            }
            if (source.getSupplierId() != null && !source.getSupplierId().equals(invoice.getSupplierId())) {
                throw new IllegalStateException("El proveedor de la factura no coincide con el albaran");
            }
            if (inputs.existsOtherInvoiceForDeliveryNote(invoice.getId(), sourceId)) {
                throw new IllegalStateException("El albaran ya esta vinculado a otra factura");
            }
            source.getLines().forEach(line -> expected.merge(
                    line.getProductId(), line.getQuantity(), BigDecimal::add));
        }
        var actual = new LinkedHashMap<UUID, BigDecimal>();
        invoice.getLines().forEach(line -> actual.merge(
                line.getProductId(), line.getQuantity(), BigDecimal::add));
        if (!sameQuantities(expected, actual)) {
            throw new IllegalStateException("Las lineas de la factura vinculada no coinciden con sus albaranes");
        }
    }

    private static boolean sameQuantities(
            Map<UUID, BigDecimal> expected,
            Map<UUID, BigDecimal> actual) {
        return expected.size() == actual.size()
                && expected.entrySet().stream().allMatch(entry -> actual.containsKey(entry.getKey())
                && actual.get(entry.getKey()).compareTo(entry.getValue()) == 0);
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
