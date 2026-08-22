package com.tpverp.backend.goodscheck;

import com.tpverp.backend.catalog.ProductIdentifierRepository;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.inventory.WarehouseInput;
import com.tpverp.backend.inventory.WarehouseInputDocumentType;
import com.tpverp.backend.inventory.WarehouseInputLine;
import com.tpverp.backend.inventory.WarehouseInputRepository;
import com.tpverp.backend.inventory.WarehouseInputStatus;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GoodsCheckService {

    private final GoodsCheckRepository checks;
    private final WarehouseInputRepository inputs;
    private final ProductRepository products;
    private final ProductIdentifierRepository identifiers;
    private final CurrentOrganization organization;
    private final CurrentTerminal currentTerminal;
    private final SyncOutboxService syncOutbox;
    private final Clock clock;

    public GoodsCheckService(
            GoodsCheckRepository checks,
            WarehouseInputRepository inputs,
            ProductRepository products,
            ProductIdentifierRepository identifiers,
            CurrentOrganization organization,
            CurrentTerminal currentTerminal,
            SyncOutboxService syncOutbox,
            Clock clock) {
        this.checks = checks;
        this.inputs = inputs;
        this.products = products;
        this.identifiers = identifiers;
        this.organization = organization;
        this.currentTerminal = currentTerminal;
        this.syncOutbox = syncOutbox;
        this.clock = clock;
    }

    @Transactional
    public GoodsCheckView start(UUID documentId, Authentication authentication) {
        var document = purchaseDocument(documentId);
        if (checks.existsByDocumentoIdAndEstado(documentId, GoodsCheckStatus.ABIERTA)) {
            throw new IllegalStateException("message.goods_check.open_exists");
        }
        var check = new GoodsCheck(
                document.getId(),
                document.getStoreId(),
                organization.currentUser(authentication).getId(),
                Instant.now(clock));
        expected(document).forEach(check::addLine);
        var saved = checks.save(check);
        enqueue(saved, SyncOperation.CREAR, null);
        return view(saved, document);
    }

    @Transactional
    public GoodsCheckView importDocument(UUID documentId, Authentication authentication) {
        var document = purchaseDocument(documentId);
        return checks.findByDocumentoIdAndEstadoAndTiendaId(
                        documentId, GoodsCheckStatus.ABIERTA, organization.currentStore().getId())
                .map(check -> view(check, document))
                .orElseGet(() -> start(documentId, authentication));
    }
    // Starts one open goods check from a confirmed incoming document.

    @Transactional(readOnly = true)
    public GoodsCheckView get(UUID id) {
        var check = find(id);
        return view(check, purchaseDocument(check.getDocumentoId()));
    }

    @Transactional
    public GoodsCheckView scan(UUID id, GoodsCheckScanRequest request, Authentication authentication) {
        var check = find(id);
        var document = purchaseDocument(check.getDocumentoId());
        var productId = resolveProduct(document, request);
        check.register(
                productId,
                request.quantity(),
                organization.currentUser(authentication).getId(),
                currentTerminal.terminalId(authentication),
                Instant.now(clock));
        var saved = checks.save(check);
        enqueue(saved, SyncOperation.ACTUALIZAR, currentTerminal.terminalId(authentication));
        return view(saved, document);
    }
    // Adds or subtracts a counted quantity for a product that already exists in the document.

    @Transactional
    public GoodsCheckView close(UUID id, Authentication authentication) {
        var check = find(id);
        check.close(organization.currentUser(authentication).getId(), Instant.now(clock));
        var saved = checks.save(check);
        enqueue(saved, SyncOperation.CERRAR, null);
        return view(saved, purchaseDocument(saved.getDocumentoId()));
    }

    private WarehouseInput purchaseDocument(UUID id) {
        var storeId = organization.currentStore().getId();
        return inputs.findByIdAndStoreId(id, storeId)
                .filter(GoodsCheckService::isAllowedPurchaseDocument)
                .filter(GoodsCheckService::isConfirmed)
                .orElseThrow(() -> new IllegalArgumentException("message.goods_check.document_not_found"));
    }

    private GoodsCheck find(UUID id) {
        return checks.findByIdAndTiendaId(id, organization.currentStore().getId())
                .orElseThrow(() -> new IllegalArgumentException("message.goods_check.not_found"));
    }

    private static boolean isAllowedPurchaseDocument(WarehouseInput document) {
        return document.getDocumentType() == WarehouseInputDocumentType.ALBARAN_ENTRADA
                || document.getDocumentType() == WarehouseInputDocumentType.FACTURA_ENTRADA;
    }

    private static boolean isConfirmed(WarehouseInput document) {
        return document.getNumber() != null
                && document.getStatus() == WarehouseInputStatus.CONFIRMADA;
    }

    private static Map<UUID, BigDecimal> expected(WarehouseInput document) {
        var result = new LinkedHashMap<UUID, BigDecimal>();
        for (var line : document.getLines()) {
            result.merge(line.getProductId(), line.getQuantity(), BigDecimal::add);
        }
        return result;
    }

    private UUID resolveProduct(WarehouseInput document, GoodsCheckScanRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.productId() != null) {
            requireProductInDocument(document, request.productId());
            return request.productId();
        }
        var code = normalized(request.code());
        var fromLine = document.getLines().stream()
                .map(WarehouseInputLine::getProductId)
                .map(products::findById)
                .flatMap(Optional::stream)
                .filter(product -> normalized(product.getCode()).equals(code))
                .map(Product::getId)
                .findFirst();
        if (fromLine.isPresent()) {
            return fromLine.get();
        }
        var productId = identifiers.findByStoreIdAndValor(document.getStoreId(), code)
                .map(value -> value.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("message.goods_check.product_not_in_document"));
        requireProductInDocument(document, productId);
        return productId;
    }

    private static void requireProductInDocument(WarehouseInput document, UUID productId) {
        if (document.getLines().stream().noneMatch(line -> line.getProductId().equals(productId))) {
            throw new IllegalArgumentException("message.goods_check.product_not_in_document");
        }
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("message.goods_check.product_required");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private GoodsCheckView view(GoodsCheck check, WarehouseInput document) {
        var labels = labels(document);
        var all = check.getLineas().stream()
                .map(line -> item(line, labels.getOrDefault(
                        line.getProductoId(),
                        new ProductLabel(line.getProductoId().toString(), ""))))
                .toList();
        return new GoodsCheckView(
                check.getId(),
                check.getDocumentoId(),
                check.getEstado(),
                all,
                all.stream().filter(item -> item.missingQuantity().signum() > 0).toList(),
                all.stream().filter(item -> item.registeredQuantity().signum() > 0).toList());
    }

    private Map<UUID, ProductLabel> labels(WarehouseInput document) {
        var labels = new LinkedHashMap<UUID, ProductLabel>();
        var ids = document.getLines().stream().map(WarehouseInputLine::getProductId).toList();
        for (var product : products.findAllByStoreIdAndIdIn(document.getStoreId(), ids)) {
            labels.putIfAbsent(product.getId(), new ProductLabel(product.getCode(), product.getName()));
        }
        return labels;
    }

    private static GoodsCheckView.Item item(GoodsCheckLine line, ProductLabel label) {
        var missing = line.getCantidadEsperada().subtract(line.getCantidadRegistrada()).max(BigDecimal.ZERO);
        var extra = line.getCantidadRegistrada().subtract(line.getCantidadEsperada()).max(BigDecimal.ZERO);
        return new GoodsCheckView.Item(
                line.getProductoId(),
                label.code(),
                label.name(),
                line.getCantidadEsperada(),
                line.getCantidadRegistrada(),
                missing,
                extra);
    }

    private void enqueue(GoodsCheck check, SyncOperation operation, UUID terminalId) {
        syncOutbox.enqueue(new SyncOutboundEventCommand(
                organization.currentCompany().getId(),
                check.getTiendaId(),
                terminalId,
                "GOODS_CHECK",
                check.getId(),
                operation,
                Map.of(
                        "documentoId", check.getDocumentoId().toString(),
                        "estado", check.getEstado().name())));
    }

    private record ProductLabel(String code, String name) {
    }
}
