package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.ProductType;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.document.DocumentCounter;
import com.tpverp.backend.document.DocumentCounterRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.shared.api.PagedResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehouseTransferDocumentService {
    public static final int MAX_DOCUMENT_LINES = 5000;
    private final WarehouseTransferDocumentRepository documents;
    private final WarehouseTransferLineRepository lines;
    private final WarehouseRepository warehouses;
    private final ProductRepository products;
    private final DocumentCounterRepository counters;
    private final InventoryService inventory;
    private final CurrentOrganization organization;
    private final Clock clock;

    public WarehouseTransferDocumentService(WarehouseTransferDocumentRepository documents,
            WarehouseTransferLineRepository lines, WarehouseRepository warehouses, ProductRepository products,
            DocumentCounterRepository counters, InventoryService inventory, CurrentOrganization organization,
            Clock clock) {
        this.documents = documents;
        this.lines = lines;
        this.warehouses = warehouses;
        this.products = products;
        this.counters = counters;
        this.inventory = inventory;
        this.organization = organization;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PagedResult<ListItem> list(Integer requestedPage, Integer requestedLimit,
            WarehouseTransferDocument.Status status, UUID sourceWarehouseId, UUID targetWarehouseId,
            Instant from, Instant before, String search) {
        int page = requestedPage == null ? 0 : Math.max(0, requestedPage);
        int limit = requestedLimit == null ? 50 : Math.max(1, Math.min(requestedLimit, 100));
        if (from != null && before != null && !from.isBefore(before)) {
            throw new IllegalArgumentException("El intervalo de fechas del traspaso no es válido");
        }
        String pattern = search == null || search.isBlank() ? null
                : "%" + search.trim().toLowerCase(Locale.ROOT)
                        .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var result = documents.pageFiltered(organization.currentStore().getId(), status,
                sourceWarehouseId, targetWarehouseId, from, before, pattern, PageRequest.of(page, limit));
        var pageItems = result.getContent();
        Map<UUID, WarehouseTransferLineTotals> totals = pageItems.isEmpty() ? Map.of()
                : lines.totalsForTransfers(pageItems.stream().map(WarehouseTransferDocument::getId).toList())
                        .stream().collect(Collectors.toMap(WarehouseTransferLineTotals::transferId, Function.identity()));
        var items = pageItems.stream().map(document -> ListItem.from(document, totals.get(document.getId()))).toList();
        return new PagedResult<>(items, result.hasNext() ? String.valueOf(page + 1) : null,
                result.hasNext());
    }

    @Transactional(readOnly = true)
    public View get(UUID id) { return view(find(id)); }

    @Transactional
    public View create(Command command, Authentication authentication) {
        var storeId = organization.currentStore().getId();
        validate(command, storeId);
        var document = new WarehouseTransferDocument(storeId, command.sourceWarehouseId(),
                command.targetWarehouseId(), command.notes(),
                organization.currentUser(authentication).getId(), Instant.now(clock));
        var prepared = prepareLines(document.getId(), command, storeId);
        applyValuation(document, command, prepared);
        documents.save(document);
        lines.saveAll(prepared);
        lines.flush();
        return view(document);
    }

    @Transactional
    public View update(UUID id, Command command) {
        var document = locked(id);
        document.requireDraft();
        if (command.expectedVersion() == null || command.expectedVersion() != document.getVersion()) {
            throw new IllegalStateException("El traspaso cambió; vuelve a abrirlo");
        }
        validate(command, document.getStoreId());
        var prepared = prepareLines(document.getId(), command, document.getStoreId());
        document.update(command.sourceWarehouseId(), command.targetWarehouseId(), command.notes());
        applyValuation(document, command, prepared);
        lines.deleteByTransferId(document.getId());
        lines.flush();
        lines.saveAll(prepared);
        lines.flush();
        return view(documents.saveAndFlush(document));
    }

    @Transactional
    public View confirm(UUID id, Long expectedVersion, Authentication authentication) {
        var document = locked(id);
        if (document.getStatus() == WarehouseTransferDocument.Status.CONFIRMED) return view(document);
        document.requireDraft();
        if (expectedVersion == null || expectedVersion != document.getVersion()) {
            throw new IllegalStateException("El traspaso cambió; vuelve a abrirlo");
        }
        validateWarehouses(document.getSourceWarehouseId(), document.getTargetWarehouseId(), document.getStoreId());
        var savedLines = lines.findByTransferIdOrderByCode(id);
        if (savedLines.isEmpty()) throw new IllegalStateException("El traspaso no tiene productos");
        Map<UUID, BigDecimal> quantities = new LinkedHashMap<>();
        savedLines.forEach(line -> quantities.merge(line.getProductId(), line.getQuantity(), BigDecimal::add));
        inventory.transferBatch(quantities.entrySet().stream().map(line -> new InventoryService.TransferCommand(
                line.getKey(), document.getSourceWarehouseId(), document.getTargetWarehouseId(),
                line.getValue())).toList(), authentication, document.getId());
        var date = LocalDate.now(clock);
        var counter = counters.findByTiendaIdAndTipoAndPeriodo(document.getStoreId(), "TRA",
                Integer.toString(date.getYear())).orElseGet(() -> DocumentCounter.traspasoAlmacen(
                        document.getStoreId(), date));
        document.confirm(counter.siguienteTraspasoAlmacen(date),
                organization.currentUser(authentication).getId(), Instant.now(clock));
        counters.saveAndFlush(counter);
        return view(documents.saveAndFlush(document));
    }

    @Transactional
    public View cancel(UUID id) {
        var document = locked(id);
        document.cancel();
        return view(documents.saveAndFlush(document));
    }

    private void validate(Command command, UUID storeId) {
        if (command == null || command.lines() == null || command.lines().isEmpty() || command.lines().size() > MAX_DOCUMENT_LINES) {
            throw new IllegalArgumentException("El traspaso debe contener entre 1 y 5000 líneas");
        }
        if (command.notes() != null && command.notes().length() > 4000) {
            throw new IllegalArgumentException("Los comentarios no pueden superar 4000 caracteres");
        }
        validateWarehouses(command.sourceWarehouseId(), command.targetWarehouseId(), storeId);
        WarehouseTransferLine.percent(command.globalDiscount());
        for (var line : command.lines()) {
            if (line == null || line.productId() == null) throw new IllegalArgumentException("Producto obligatorio");
            var selectedProduct = product(line.productId(), storeId);
            if (line.quantity() == null || line.quantity().signum() <= 0
                    || line.quantity().stripTrailingZeros().scale() > 3) {
                throw new IllegalArgumentException("Cantidad de traspaso inválida");
            }
            if (selectedProduct.getProductType() == ProductType.UNIT
                    && line.quantity().stripTrailingZeros().scale() > 0) {
                throw new IllegalArgumentException("message.product.unit_quantity_must_be_integer");
            }
        }
    }

    private void validateWarehouses(UUID source, UUID target, UUID storeId) {
        if (source == null || target == null || source.equals(target)) {
            throw new IllegalArgumentException("Selecciona dos almacenes distintos");
        }
        for (var id : List.of(source, target)) {
            var warehouse = warehouses.findById(id).orElseThrow(() -> new IllegalArgumentException("Almacén no encontrado"));
            if (!storeId.equals(warehouse.getStoreId()) || !warehouse.isActive()) {
                throw new IllegalArgumentException("Almacén no disponible para esta tienda");
            }
        }
    }

    private Product product(UUID id, UUID storeId) {
        var product = products.findById(id).orElseThrow(() -> new IllegalArgumentException("Producto no encontrado"));
        if (!storeId.equals(product.getStoreId()) || product.getProductType() == ProductType.SERVICE) {
            throw new IllegalArgumentException("Producto no disponible para traspaso");
        }
        return product;
    }

    private List<WarehouseTransferLine> prepareLines(UUID id, Command command, UUID storeId) {
        var result = new ArrayList<WarehouseTransferLine>();
        for (var line : command.lines()) {
            var selected = product(line.productId(), storeId);
            if (line.unitPrice() != null) {
                var price = com.tpverp.backend.document.Money.exactUnitPrice(line.unitPrice());
                if (price.signum() < 0) throw new IllegalArgumentException("El precio no puede ser negativo");
            }
            if (line.priceOverridden() && line.unitPrice() == null) {
                throw new IllegalArgumentException("Una linea con precio personalizado necesita importe");
            }
            var price = line.priceOverridden() ? line.unitPrice() : command.priceSource().price(selected);
            result.add(new WarehouseTransferLine(id, selected, line.quantity(), price,
                    line.discount(), line.priceOverridden(), line.productName(), result.size() + 1));
        }
        return result;
    }

    private void applyValuation(WarehouseTransferDocument document, Command command, List<WarehouseTransferLine> prepared) {
        document.valuation(command.date() == null ? document.getDate() : command.date(), command.externalNumber(),
                command.priceSource(), command.globalDiscount(),
                prepared.stream().map(WarehouseTransferLine::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private WarehouseTransferDocument find(UUID id) {
        return documents.findByIdAndStoreId(id, organization.currentStore().getId())
                .orElseThrow(() -> new IllegalArgumentException("Traspaso no encontrado"));
    }
    private WarehouseTransferDocument locked(UUID id) {
        return documents.findLockedByIdAndStoreId(id, organization.currentStore().getId())
                .orElseThrow(() -> new IllegalArgumentException("Traspaso no encontrado"));
    }
    private View view(WarehouseTransferDocument document) {
        return new View(document, lines.findByTransferIdOrderByCode(document.getId()).stream()
                .sorted(Comparator.comparingInt(WarehouseTransferLine::getPosition)).toList());
    }

    public record LineCommand(UUID productId, BigDecimal quantity, BigDecimal unitPrice, BigDecimal discount,
                              boolean priceOverridden, String productName) {
        public LineCommand(UUID productId, BigDecimal quantity) {
            this(productId, quantity, null, BigDecimal.ZERO, false, null);
        }
    }
    public record ListItem(UUID id, UUID storeId, UUID sourceWarehouseId, UUID targetWarehouseId,
            String number, WarehouseTransferDocument.Status status, String notes, Instant createdAt,
            long version, long lineCount, BigDecimal totalUnits) {
        static ListItem from(WarehouseTransferDocument document, WarehouseTransferLineTotals totals) {
            return new ListItem(document.getId(), document.getStoreId(), document.getSourceWarehouseId(),
                    document.getTargetWarehouseId(), document.getNumber(), document.getStatus(),
                    document.getNotes(), document.getCreatedAt(), document.getVersion(),
                    totals == null ? 0 : totals.lineCount(),
                    totals == null ? BigDecimal.ZERO : totals.totalUnits());
        }
    }
    public record Command(UUID sourceWarehouseId, UUID targetWarehouseId, String notes,
                          Long expectedVersion, List<LineCommand> lines, LocalDate date, String externalNumber,
                          WarehouseInputPriceSource priceSource, BigDecimal globalDiscount) {
        public Command {
            priceSource = priceSource == null ? WarehouseInputPriceSource.PURCHASE : priceSource;
        }
        public Command(UUID sourceWarehouseId, UUID targetWarehouseId, String notes,
                       Long expectedVersion, List<LineCommand> lines) {
            this(sourceWarehouseId, targetWarehouseId, notes, expectedVersion, lines, null, null,
                    WarehouseInputPriceSource.PURCHASE, BigDecimal.ZERO);
        }
    }
    public record View(WarehouseTransferDocument document, List<WarehouseTransferLine> lines) {}
}
