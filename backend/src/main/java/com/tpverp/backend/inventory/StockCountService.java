package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.FamilyRepository;
import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.ProductType;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.document.DocumentCounter;
import com.tpverp.backend.document.DocumentCounterRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
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
    private final DocumentCounterRepository counters;
    private final UserAccountRepository users;
    private final FamilyRepository families;

    public StockCountService(
            StockCountRepository counts, StockCountLineRepository lines,
            StockLevelRepository stocks, StockMovementRepository movements,
            ProductRepository products, WarehouseRepository warehouses,
            CurrentOrganization organization, StockMovementSyncPublisher syncPublisher, Clock clock,
            DocumentCounterRepository counters, UserAccountRepository users, FamilyRepository families) {
        this.counts = counts;
        this.lines = lines;
        this.stocks = stocks;
        this.movements = movements;
        this.products = products;
        this.warehouses = warehouses;
        this.organization = organization;
        this.syncPublisher = syncPublisher;
        this.clock = clock;
        this.counters = counters;
        this.users = users;
        this.families = families;
    }

    @Transactional(readOnly = true)
    public StockCountResources resources() {
        var storeId = organization.currentStore().getId();
        return new StockCountResources(
                warehouses.findByStoreIdOrderByNombre(storeId).stream()
                        .map(value -> new StockCountResources.Warehouse(value.getId(), value.getName(), value.isActive())).toList(),
                products.findByStoreIdOrderByNombre(storeId).stream()
                        .map(value -> new StockCountResources.Product(value.getId(), value.getCode(), value.getBarcode(),
                                value.getName(), value.isActive(), value.getProductType(), value.getFamilyId())).toList(),
                families.findByStoreIdOrderByFamilyCodeAscIdAsc(storeId).stream()
                        .map(value -> new StockCountResources.Family(value.getId(), value.getName())).toList());
    }

    @Transactional(readOnly = true)
    public List<StockCountResources.Balance> balances(UUID warehouseId) {
        warehouseInStore(warehouseId, organization.currentStore().getId());
        return stocks.findByWarehouseId(warehouseId).stream()
                .map(value -> new StockCountResources.Balance(value.getProductId(), value.getWarehouseId(), value.getQuantity())).toList();
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
            var date = LocalDate.now(clock);
            var counter = counters.findByTiendaIdAndTipoAndPeriodo(storeId, "INV", Integer.toString(date.getYear()))
                    .orElseGet(() -> DocumentCounter.inventario(storeId, date));
            var count = new StockCount(storeId, warehouseId, notes, user.getId(), Instant.now(clock));
            count.editDraft(date, notes);
            count.number(counter.siguienteInventario(date));
            counters.saveAndFlush(counter);
            count = counts.saveAndFlush(count);
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
        var filtered = values.stream().filter(value -> warehouseId == null || warehouseId.equals(value.getWarehouseId())).toList();
        var creatorNames = users.findAllById(filtered.stream().map(StockCount::getCreatedBy).distinct().toList()).stream()
                .collect(Collectors.toMap(UserAccount::getId, UserAccount::getNombre));
        return filtered.stream().map(value -> summary(value, creatorNames.get(value.getCreatedBy()))).toList();
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
        var line = lines.findByCountIdAndProductId(id, productId)
                .orElseGet(() -> new StockCountLine(id, productId, currentQuantity(productId, count.getWarehouseId()), counted));
        line.update(line.getExpectedQuantity(), counted);
        line.snapshot(product.getCode(), product.getBarcode(), product.getName());
        lines.save(line);
        count.editDraft(count.getDocumentDate(), count.getNotes());
        return view(counts.saveAndFlush(count));
    }

    @Transactional
    public StockCountView saveDraft(UUID id, long expectedVersion, LocalDate documentDate, String notes,
                                    List<StockCountController.DraftLineRequest> requestedLines) {
        var count = locked(id);
        count.requireDraft();
        requireVersion(count, expectedVersion);
        if (documentDate == null || requestedLines == null) throw new IllegalArgumentException("La fecha y las líneas son obligatorias");
        var existing = lines.findByCountIdOrderByProductId(id);
        var existingByProduct = existing.stream().collect(Collectors.toMap(StockCountLine::getProductId, value -> value));
        var seen = new HashSet<UUID>();
        var prepared = new ArrayList<PreparedLine>();
        for (var requested : requestedLines) {
            if (requested == null || requested.productId() == null || !seen.add(requested.productId()))
                throw new IllegalArgumentException("El inventario contiene productos vacíos o duplicados");
            var product = product(requested.productId(), count.getStoreId());
            var counted = requested.countedQuantity() == null ? null : quantity(requested.countedQuantity());
            if (counted != null) {
                if (counted.signum() < 0) throw new IllegalArgumentException("La cantidad contada no puede ser negativa");
                validateProductQuantity(product, counted);
            }
            var line = existingByProduct.get(product.getId());
            var expected = line == null ? currentQuantity(product.getId(), count.getWarehouseId()) : line.getExpectedQuantity();
            if (requested.expectedQuantity() != null) {
                var reviewed = quantity(requested.expectedQuantity());
                if (line == null || reviewed.compareTo(expected) != 0) {
                    if (reviewed.compareTo(currentQuantity(product.getId(), count.getWarehouseId())) != 0)
                        throw new IllegalStateException("El stock cambió. Revisa las existencias antes de actualizar la referencia");
                    expected = reviewed;
                }
            }
            prepared.add(new PreparedLine(line, product, expected, counted));
        }
        lines.deleteAll(existing.stream().filter(line -> !seen.contains(line.getProductId())).toList());
        for (var value : prepared) {
            var line = value.line() == null
                    ? new StockCountLine(id, value.product().getId(), value.expected(), value.counted()) : value.line();
            line.update(value.expected(), value.counted());
            line.snapshot(value.product().getCode(), value.product().getBarcode(), value.product().getName());
            lines.save(line);
        }
        count.editDraft(documentDate, notes);
        return view(counts.saveAndFlush(count));
    }
    private record PreparedLine(StockCountLine line, Product product, BigDecimal expected, BigDecimal counted) {}

    @Transactional
    public StockCountView confirm(UUID id, Authentication authentication) {
        return confirm(id, null, authentication);
    }

    @Transactional
    public StockCountView confirm(UUID id, List<StockCountView.Line> reviewedLines, Authentication authentication) {
        return confirm(id, null, reviewedLines, authentication);
    }

    @Transactional
    public StockCountView confirm(UUID id, Long expectedVersion, List<StockCountView.Line> reviewedLines, Authentication authentication) {
        var count = locked(id);
        if (count.getStatus() == StockCountStatus.CONFIRMED) return view(count);
        count.requireDraft();
        if (expectedVersion != null) requireVersion(count, expectedVersion);
        var countLines = lines.findByCountIdOrderByProductId(id);
        if (countLines.stream().anyMatch(line -> line.getCountedQuantity() == null))
            throw new IllegalStateException("Hay líneas pendientes de contar");
        if (reviewedLines != null && (reviewedLines.size() != countLines.size()
                || countLines.stream().anyMatch(line -> reviewedLines.stream().noneMatch(reviewed ->
                    reviewed != null && reviewed.expectedQuantity() != null && reviewed.countedQuantity() != null
                    && line.getProductId().equals(reviewed.productId())
                    && line.getExpectedQuantity().compareTo(reviewed.expectedQuantity()) == 0
                    && line.getCountedQuantity().compareTo(reviewed.countedQuantity()) == 0)))) {
            throw new IllegalStateException("El inventario cambió. Revisa las cantidades antes de confirmar");
        }
        if (countLines.isEmpty()) throw new IllegalStateException("No se puede confirmar un recuento sin lineas");
        if (movements.existsByStockCountId(id)) {
            throw new IllegalStateException("El recuento ya tiene movimientos sin estar confirmado");
        }
        var user = organization.currentUser(authentication);
        for (var line : countLines.stream().sorted(Comparator.comparing(StockCountLine::getProductId)).toList()) {
            var stock = stocks.findByProductIdAndWarehouseIdForUpdate(line.getProductId(), count.getWarehouseId())
                    .orElseGet(() -> new StockLevel(line.getProductId(), count.getWarehouseId()));
            if (stock.getQuantity().compareTo(line.getExpectedQuantity()) != 0) {
                throw new IllegalStateException("El stock cambio durante el recuento; revise la existencia de referencia de " + line.getProductId());
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
        return view(counts.saveAndFlush(count));
    }

    @Transactional
    public StockCountView cancel(UUID id, Authentication authentication) {
        var count = locked(id);
        count.cancel(organization.currentUser(authentication).getId(), Instant.now(clock));
        return view(counts.saveAndFlush(count));
    }

    private StockCount find(UUID id) {
        return counts.findByIdAndStoreId(id, organization.currentStore().getId())
                .orElseThrow(() -> new IllegalArgumentException("Recuento no encontrado"));
    }
    private static void requireVersion(StockCount count, long expectedVersion) {
        if (count.getVersion() != expectedVersion)
            throw new IllegalStateException("El inventario cambió. Recarga el documento antes de guardar o confirmar");
    }
    private BigDecimal currentQuantity(UUID productId, UUID warehouseId) {
        return stocks.findByProductIdAndWarehouseId(productId, warehouseId)
                .map(StockLevel::getQuantity).orElse(BigDecimal.ZERO).setScale(3);
    }
    private StockCount locked(UUID id) {
        return counts.findLockedByIdAndStoreId(id, organization.currentStore().getId())
                .orElseThrow(() -> new IllegalArgumentException("Recuento no encontrado"));
    }
    private void warehouse(UUID id, UUID storeId) {
        var warehouse = warehouseInStore(id, storeId);
        if (!warehouse.isActive()) throw new IllegalStateException("El almacen no esta activo");
    }
    private Warehouse warehouseInStore(UUID id, UUID storeId) {
        var warehouse = warehouses.findById(id).orElseThrow(() -> new IllegalArgumentException("Almacen no encontrado"));
        if (!warehouse.getStoreId().equals(storeId)) throw new IllegalArgumentException("El almacen no pertenece a la tienda actual");
        return warehouse;
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
            return new StockCountView.Line(product.getId(), line.getProductCode() == null ? product.getCode() : line.getProductCode(),
                    line.getProductBarcode() == null ? product.getBarcode() : line.getProductBarcode(),
                    line.getProductName() == null ? product.getName() : line.getProductName(),
                    line.getExpectedQuantity(), line.getCountedQuantity(), line.difference(), line.getAppliedDifference());
        }).toList();
        return new StockCountView(count.getId(), count.getNumber(), count.getStoreId(), count.getWarehouseId(), count.getStatus(), count.getNotes(),
                count.getCreatedBy(), count.getCreatedAt(), count.getConfirmedBy(), count.getConfirmedAt(),
                count.getCancelledBy(), count.getCancelledAt(), detailLines, count.getVersion(), count.getDocumentDate(),
                users.findById(count.getCreatedBy()).map(UserAccount::getNombre).orElse(null));
    }
    private StockCountSummary summary(StockCount count, String createdByName) {
        var countLines = lines.findByCountIdOrderByProductId(count.getId());
        var total = countLines.stream().map(StockCountLine::difference).filter(Objects::nonNull).reduce(BigDecimal.ZERO.setScale(3), BigDecimal::add);
        return new StockCountSummary(count.getId(), count.getNumber(), count.getStoreId(), count.getWarehouseId(), count.getStatus(), count.getNotes(),
                count.getCreatedBy(), count.getCreatedAt(), count.getConfirmedBy(), count.getConfirmedAt(),
                count.getCancelledBy(), count.getCancelledAt(), countLines.size(), total, count.getVersion(), count.getDocumentDate(), createdByName);
    }
}
