package com.tpverp.backend.goodscheck;

import com.tpverp.backend.catalog.ProductIdentifierRepository;
import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentLine;
import com.tpverp.backend.document.DocumentStatus;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GoodsCheckService {

    private final GoodsCheckRepository checks;
    private final CommercialDocumentRepository documents;
    private final ProductIdentifierRepository identifiers;
    private final CurrentOrganization organization;
    private final CurrentTerminal currentTerminal;
    private final SyncOutboxService syncOutbox;
    private final Clock clock;

    public GoodsCheckService(
            GoodsCheckRepository checks,
            CommercialDocumentRepository documents,
            ProductIdentifierRepository identifiers,
            CurrentOrganization organization,
            CurrentTerminal currentTerminal,
            SyncOutboxService syncOutbox,
            Clock clock) {
        this.checks = checks;
        this.documents = documents;
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
                document.getTiendaId(),
                organization.currentUser(authentication).getId(),
                Instant.now(clock));
        expected(document).forEach(check::addLine);
        var saved = checks.save(check);
        enqueue(saved, SyncOperation.CREAR, null);
        return view(saved, document);
    }
    // Starts one open goods check from a confirmed purchase document.

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

    private CommercialDocument purchaseDocument(UUID id) {
        var storeId = organization.currentStore().getId();
        return documents.findById(id)
                .filter(document -> document.getTiendaId().equals(storeId))
                .filter(GoodsCheckService::isAllowedPurchaseDocument)
                .filter(GoodsCheckService::isConfirmed)
                .orElseThrow(() -> new IllegalArgumentException("message.goods_check.document_not_found"));
    }

    private GoodsCheck find(UUID id) {
        return checks.findByIdAndTiendaId(id, organization.currentStore().getId())
                .orElseThrow(() -> new IllegalArgumentException("message.goods_check.not_found"));
    }

    private static boolean isAllowedPurchaseDocument(CommercialDocument document) {
        return document.getTipo() == CommercialDocumentType.ALBARAN_COMPRA
                || document.getTipo() == CommercialDocumentType.FACTURA_COMPRA;
    }

    private static boolean isConfirmed(CommercialDocument document) {
        return document.getNumero() != null
                && document.getEstado() != DocumentStatus.BORRADOR
                && document.getEstado() != DocumentStatus.ANULADO;
    }

    private static Map<UUID, Integer> expected(CommercialDocument document) {
        var result = new LinkedHashMap<UUID, Integer>();
        for (var line : document.getLineas()) {
            result.merge(line.getProductoId(), line.getCantidad(), Integer::sum);
        }
        return result;
    }

    private UUID resolveProduct(CommercialDocument document, GoodsCheckScanRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.productId() != null) {
            requireProductInDocument(document, request.productId());
            return request.productId();
        }
        var code = normalized(request.code());
        var fromLine = document.getLineas().stream()
                .filter(line -> normalized(line.getCodigo()).equals(code))
                .map(DocumentLine::getProductoId)
                .findFirst();
        if (fromLine.isPresent()) {
            return fromLine.get();
        }
        var productId = identifiers.findByStoreIdAndValor(document.getTiendaId(), code)
                .map(value -> value.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("message.goods_check.product_not_in_document"));
        requireProductInDocument(document, productId);
        return productId;
    }

    private static void requireProductInDocument(CommercialDocument document, UUID productId) {
        if (document.getLineas().stream().noneMatch(line -> line.getProductoId().equals(productId))) {
            throw new IllegalArgumentException("message.goods_check.product_not_in_document");
        }
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("message.goods_check.product_required");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private GoodsCheckView view(GoodsCheck check, CommercialDocument document) {
        var labels = labels(document);
        var all = check.getLineas().stream()
                .map(line -> item(line, labels.get(line.getProductoId())))
                .toList();
        return new GoodsCheckView(
                check.getId(),
                check.getDocumentoId(),
                check.getEstado(),
                all,
                all.stream().filter(item -> item.missingQuantity() > 0).toList(),
                all.stream().filter(item -> item.registeredQuantity() > 0).toList());
    }

    private static Map<UUID, ProductLabel> labels(CommercialDocument document) {
        var labels = new LinkedHashMap<UUID, ProductLabel>();
        for (var line : document.getLineas()) {
            labels.putIfAbsent(line.getProductoId(), new ProductLabel(line.getCodigo(), line.getNombre()));
        }
        return labels;
    }

    private static GoodsCheckView.Item item(GoodsCheckLine line, ProductLabel label) {
        var missing = Math.max(0, line.getCantidadEsperada() - line.getCantidadRegistrada());
        var extra = Math.max(0, line.getCantidadRegistrada() - line.getCantidadEsperada());
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
