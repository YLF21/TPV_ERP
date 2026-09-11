package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.ProductSupplierRepository;
import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.document.DocumentCounter;
import com.tpverp.backend.document.DocumentCounterRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.SupplierRepository;
import com.tpverp.backend.security.application.PermissionChecks;
import com.tpverp.backend.shared.api.PagedResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final ProductSupplierRepository productSuppliers;
    private final WarehouseInputExcelAuditService excelAudit;
    private final WarehouseExcelImportProvenanceService provenance;

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
            ProductSupplierRepository productSuppliers,
            StockMovementSyncPublisher syncPublisher,
            Clock clock) {
        this(inputs, counters, stockLevels, settings, movements, organization, products, warehouses, suppliers,
                productSuppliers, syncPublisher, clock, null, null);
    }

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
            ProductSupplierRepository productSuppliers,
            StockMovementSyncPublisher syncPublisher,
            Clock clock,
            WarehouseInputExcelAuditService excelAudit) {
        this(inputs, counters, stockLevels, settings, movements, organization, products, warehouses, suppliers,
                productSuppliers, syncPublisher, clock, excelAudit, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
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
            ProductSupplierRepository productSuppliers,
            StockMovementSyncPublisher syncPublisher,
            Clock clock,
            WarehouseInputExcelAuditService excelAudit,
            WarehouseExcelImportProvenanceService provenance) {
        this.inputs = inputs;
        this.counters = counters;
        this.stockLevels = stockLevels;
        this.settings = settings;
        this.movements = movements;
        this.organization = organization;
        this.products = products;
        this.warehouses = warehouses;
        this.suppliers = suppliers;
        this.productSuppliers = productSuppliers;
        this.syncPublisher = syncPublisher;
        this.clock = clock;
        this.excelAudit = excelAudit;
        this.provenance = provenance;
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
        return listPage(requestedLimit, cursor, type, null, null);
    }

    @Transactional(readOnly = true)
    public PagedResult<WarehouseInputView> listPage(
            Integer requestedLimit, String cursor, WarehouseInputDocumentType type,
            LocalDate dateFrom, LocalDate dateTo) {
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new IllegalArgumentException("La fecha inicial no puede ser posterior a la final");
        }
        var limit = normalizedLimit(requestedLimit);
        var parsedCursor = parseCursor(cursor);
        var values = inputs.findReportPageInRange(organization.currentStore().getId(), type,
                dateFrom, dateTo, parsedCursor.date(), parsedCursor.id(), PageRequest.of(0, limit + 1));
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
        var items = pageValues.stream().map(input -> viewOf(input, productsById)).toList();
        return new PagedResult<>(items, hasMore ? cursorFor(pageValues.get(pageValues.size() - 1)) : null, hasMore);
    }

    @Transactional(readOnly = true)
    public WarehouseInputView view(UUID id) {
        var input = find(id);
        var productIds = input.getLines().stream().map(WarehouseInputLine::getProductId).distinct().toList();
        var productsById = products.findAllByStoreIdAndIdIn(input.getStoreId(), productIds)
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        return viewOf(input, productsById);
    }

    @Transactional
    public WarehouseInputView createView(WarehouseInputCommand command, Authentication authentication) {
        return viewOf(create(command, authentication));
    }

    @Transactional
    public WarehouseInputView updateView(UUID id, WarehouseInputCommand command) {
        return viewOf(update(id, command));
    }

    @Transactional
    public WarehouseInputView confirmView(UUID id, Authentication authentication) {
        return viewOf(confirm(id, authentication));
    }

    private WarehouseInputView viewOf(WarehouseInput input) {
        var productIds = input.getLines().stream().map(WarehouseInputLine::getProductId).distinct().toList();
        var productsById = products.findAllByStoreIdAndIdIn(input.getStoreId(), productIds)
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        return viewOf(input, productsById);
    }

    private WarehouseInputView viewOf(WarehouseInput input, Map<UUID, Product> productsById) {
        return WarehouseInputView.from(input, productsById, documentSnapshotToken(input));
    }

    private String documentSnapshotToken(WarehouseInput input) {
        if (provenance == null || input.getExcelImport() == null) return null;
        return provenance.signDocument(
                organization.currentCompany().getId(), input.getStoreId(), input.getWarehouseId(), input.getDate(),
                input.getId(), input.getVersion(), input.getSupplierId(), input.getExcelImport(), input.getLines());
    }

    @Transactional
    public WarehouseInput create(WarehouseInputCommand command, Authentication authentication) {
        var store = organization.currentStore();
        var user = organization.currentUser(authentication);
        validate(command, store.getId());
        verifyExcelImportProvenance(command, store.getId());
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
        return inputs.saveAndFlush(input);
    }

    @Transactional
    public WarehouseInput update(UUID id, WarehouseInputCommand command) {
        var input = findForUpdate(id);
        validate(command, input.getStoreId());
        if (!input.getWarehouseId().equals(command.warehouseId())
                || input.getDocumentType() != command.documentType()
                || !input.getDate().equals(command.date())) {
            throw new IllegalArgumentException(
                "message.warehouse_input.warehouse_and_date_immutable");
        }
        verifyExcelImportProvenance(command, input.getStoreId());
        var newLines = valuedLines(command, input.getStoreId());
        var excelImport = excelImportForUpdate(input, command, newLines);
        // Release the persisted positions before Hibernate inserts replacement lines.
        // Flush is not a commit: any later failure rolls back the deletions as well.
        input.clearLinesForReplacement();
        inputs.flush();
        input.replace(
                command.supplierId(),
                command.origin(),
                command.externalNumber(),
                command.concept(),
                command.priceSource(),
                command.globalDiscount(),
                command.sourceDeliveryNoteIds(),
                newLines,
                excelImport);
        return inputs.saveAndFlush(input);
    }

    private WarehouseExcelImportMetadata excelImportForUpdate(
            WarehouseInput input, WarehouseInputCommand command, List<WarehouseInputLineCommand> newLines) {
        if (Boolean.TRUE.equals(command.clearExcelImport())) {
            if (input.getExcelImport() != null && input.getExcelImport().updateSupplier()) {
                throw new WarehouseExcelImportSnapshotException("EXCEL_IMPORT_REVIEW_REQUIRED");
            }
            return null;
        }
        if (command.excelImport() != null) return command.excelImport();
        var current = input.getExcelImport();
        if (current == null) {
            if (command.expectedExcelImportSnapshotToken() != null) {
                throw new WarehouseExcelImportSnapshotException("VERSION_STALE");
            }
            return null;
        }
        if (command.expectedExcelImportSnapshotToken() == null) {
            throw new WarehouseExcelImportSnapshotException("VERSION_STALE");
        }
        if (provenance == null) {
            throw new WarehouseExcelImportSnapshotException("VERSION_STALE");
        }
        boolean tokenValid = provenance.verifyDocument(
                command.expectedExcelImportSnapshotToken(), organization.currentCompany().getId(),
                input.getStoreId(), input.getWarehouseId(), input.getDate(), input.getId(), input.getVersion(),
                input.getSupplierId(), current, input.getLines());
        if (!tokenValid) throw new WarehouseExcelImportSnapshotException("VERSION_STALE");
        if (!equivalentDocumentState(input, command, newLines)) {
            throw new WarehouseExcelImportSnapshotException("EXCEL_IMPORT_REVIEW_REQUIRED");
        }
        return current;
    }

    private static boolean equivalentDocumentState(
            WarehouseInput input, WarehouseInputCommand command, List<WarehouseInputLineCommand> newLines) {
        if (!Objects.equals(input.getWarehouseId(), command.warehouseId())
                || !Objects.equals(input.getDate(), command.date())
                || input.getDocumentType() != command.documentType()
                || input.getPriceSource() != command.priceSource()
                || compare(input.getGlobalDiscount(), command.globalDiscount()) != 0
                || !Objects.equals(input.getSupplierId(), command.supplierId())
                || !input.getSourceDeliveryNoteIds().stream().sorted().toList()
                        .equals(command.sourceDeliveryNoteIds().stream().sorted().toList())) return false;
        var persisted = input.getLines();
        if (persisted.size() != newLines.size()) return false;
        for (int index = 0; index < persisted.size(); index++) {
            var oldLine = persisted.get(index);
            var newLine = newLines.get(index);
            if (!Objects.equals(oldLine.getProductId(), newLine.productId())
                    || compare(oldLine.getQuantity(), newLine.quantity()) != 0
                    || compare(oldLine.getPurchaseUnitPrice(), newLine.unitPrice()) != 0
                    || compare(oldLine.getDiscount(), newLine.discount()) != 0
                    || oldLine.isPriceOverridden() != newLine.priceOverridden()) return false;
        }
        return true;
    }

    private static int compare(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) return left == right ? 0 : left == null ? -1 : 1;
        return left.compareTo(right);
    }

    private void verifyExcelImportProvenance(WarehouseInputCommand command, UUID storeId) {
        if (command == null || command.excelImport() == null) return;
        if (provenance == null || command.excelImportProvenanceToken() == null
                || command.excelImportProvenanceToken().isBlank()) {
            throw new WarehouseExcelImportProvenanceException(
                    "La metadata Excel necesita una prueba de aplicación autoritativa");
        }
        if (command.excelImport().updateSupplier() && command.supplierId() == null) {
            throw new WarehouseExcelImportProvenanceException(
                    "La metadata Excel con actualización de proveedor necesita supplierId");
        }
        if (command.excelImport().updateSupplier()) {
            var metadataProductIds = command.excelImport().lines().stream()
                    .map(WarehouseExcelImportMetadata.Line::productId)
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            var commandProductIds = command.lines().stream()
                    .map(WarehouseInputLineCommand::productId)
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            if (!metadataProductIds.equals(commandProductIds)) {
                throw new WarehouseExcelImportProvenanceException(
                        "La prueba de aplicación no coincide con los productos del documento");
            }
        }
        UUID supplierClaim = command.excelImport().updateSupplier() ? command.supplierId() : null;
        boolean valid = provenance.verifyApply(
                command.excelImportProvenanceToken(),
                organization.currentCompany().getId(), storeId, command.warehouseId(), command.date(),
                supplierClaim, command.excelImport());
        if (!valid) {
            throw new WarehouseExcelImportProvenanceException(
                    "La prueba de aplicación de la metadata Excel no es válida");
        }
    }

    public static final class WarehouseExcelImportProvenanceException extends IllegalArgumentException {
        public WarehouseExcelImportProvenanceException(String message) {
            super(message);
        }
    }

    public static final class WarehouseExcelImportSnapshotException extends IllegalArgumentException {
        public WarehouseExcelImportSnapshotException(String message) {
            super(message);
        }
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
        var input = findForUpdate(id);
        scheduleExcelSupplierAudit(input);
        warehouse(input.getWarehouseId(), input.getStoreId());
        var user = organization.currentUser(authentication);
        validateExcelSupplierUpdate(input, authentication);
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
            applyExcelSupplierUpdate(input);
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

    private WarehouseInput findForUpdate(UUID id) {
        var storeId = organization.currentStore().getId();
        return inputs.findByIdAndStoreIdForUpdate(id, storeId)
                .orElseThrow(() -> new IllegalArgumentException("Entrada no encontrada"));
    }

    private void validateExcelSupplierUpdate(WarehouseInput input, Authentication authentication) {
        var metadata = input.getExcelImport();
        if (metadata == null || !metadata.updateSupplier()) return;
        if (!PermissionChecks.hasProductManagement(authentication)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Se necesita GESTION_PRODUCTO para actualizar proveedor-producto");
        }
        if (input.getSupplierId() == null) {
            throw new IllegalArgumentException("La entrada necesita proveedor para actualizar proveedor-producto");
        }
        var supplier = suppliers.findByIdAndCompanyIdForUpdate(
                input.getSupplierId(), organization.currentCompany().getId())
                .orElseThrow(() -> new IllegalArgumentException("Proveedor no encontrado"));
        if (!supplier.isActive()) throw new IllegalArgumentException("El proveedor esta inactivo");
        if (metadata.lines().isEmpty()) throw new IllegalArgumentException("La metadata Excel no contiene lineas");
        var metadataProducts = metadata.lines().stream().map(WarehouseExcelImportMetadata.Line::productId).toList();
        var metadataProductSet = metadataProducts.stream().collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (metadataProductSet.size() != metadataProducts.size()) {
            throw new IllegalArgumentException("La metadata Excel contiene productos repetidos");
        }
        var inputProductSet = input.getLines().stream().map(WarehouseInputLine::getProductId)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (!metadataProductSet.equals(inputProductSet)) {
            throw new IllegalArgumentException("La metadata Excel no coincide con las lineas de la entrada");
        }
        for (var line : metadata.lines()) {
            if (line.supplierReference() != null && line.supplierReference().length() > 128) {
                throw new IllegalArgumentException("La referencia del proveedor no puede superar 128 caracteres");
            }
        }
    }

    private void applyExcelSupplierUpdate(WarehouseInput input) {
        var metadata = input.getExcelImport();
        if (metadata == null || !metadata.updateSupplier()) return;
        var entryAt = input.getDate().atStartOfDay(ZoneId.of(organization.currentStore().getTimezone())).toInstant();
        var byId = metadata.lines().stream().collect(Collectors.toMap(
                WarehouseExcelImportMetadata.Line::productId, Function.identity(), (left, right) -> left,
                LinkedHashMap::new));
        // The document is already locked. The supplier lock is acquired during
        // validation, then products are reloaded and locked in repository order
        // so no pre-lock snapshot can drive the supplier upsert.
        var productsById = products.findAllByStoreIdAndIdInForUpdate(input.getStoreId(), byId.keySet()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        if (productsById.size() != byId.size()) {
            throw new IllegalArgumentException("Un producto de la metadata no pertenece a la tienda");
        }
        var orderedIds = productsById.keySet().stream().sorted().toList();
        for (var productId : orderedIds) {
            var latest = productSuppliers.findLatestEntryAtForProduct(productId);
            var last = latest == null || !entryAt.isBefore(latest);
            if (last) productSuppliers.clearLastSupplier(productId, input.getSupplierId());
            var line = byId.get(productId);
            var product = productsById.get(productId);
            var reference = line.supplierReference();
            if (reference == null || reference.isBlank()) {
                reference = product.getCode();
                if (reference == null || reference.isBlank()) reference = product.getBarcode();
            }
            var current = productSuppliers.findByProduct_IdAndSupplier_Id(productId, input.getSupplierId()).orElse(null);
            var gross = line.grossPurchasePrice() == null ? product.getPurchasePrice() : line.grossPurchasePrice();
            var discount = line.purchaseDiscountPercent() == null ? BigDecimal.ZERO : line.purchaseDiscountPercent();
            if (metadata.skipZeroPriceUpdate() && gross.signum() == 0) {
                gross = current != null && current.getGrossPurchasePrice() != null
                        ? current.getGrossPurchasePrice()
                        : product.getPurchasePrice() == null ? BigDecimal.ZERO : product.getPurchasePrice();
            }
            productSuppliers.upsertPurchase(
                    UUID.randomUUID(), productId, input.getSupplierId(), reference,
                    false, last, gross, discount, entryAt);
        }
    }

    private void scheduleExcelSupplierAudit(WarehouseInput input) {
        if (excelAudit == null || input == null || input.getExcelImport() == null
                || !input.getExcelImport().updateSupplier() || !TransactionSynchronizationManager.isSynchronizationActive()) return;
        var metadata = input.getExcelImport();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("documentId", input.getId() == null ? "" : input.getId().toString());
        details.put("supplierId", input.getSupplierId() == null ? "" : input.getSupplierId().toString());
        details.put("sha256", metadata.sha256() == null ? "" : metadata.sha256());
        details.put("lineCount", metadata.lines().size());
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    details.put("result", status == STATUS_COMMITTED ? "EXITO" : "FALLO");
                    try {
                        excelAudit.record(status == STATUS_COMMITTED ? com.tpverp.backend.audit.AuditResult.EXITO
                                : com.tpverp.backend.audit.AuditResult.FALLO, details);
                    } catch (RuntimeException ignored) {
                        // Audit failures must never change confirmation outcome.
                    }
                }
            });
        } catch (RuntimeException ignored) {
            // A non-transactional direct invocation has no safe after-commit boundary.
        }
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
