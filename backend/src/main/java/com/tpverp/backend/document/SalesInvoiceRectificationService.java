package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalesInvoiceRectificationService {

    private final CommercialDocumentRepository documents;
    private final SalesInvoiceRectificationRepository rectifications;
    private final DocumentRelationRepository relations;
    private final CurrentOrganization organization;
    private final CurrentTerminal currentTerminal;
    private final DocumentOperationalEventRecorder operationalEvents;
    private final Clock clock;

    public SalesInvoiceRectificationService(
            CommercialDocumentRepository documents,
            SalesInvoiceRectificationRepository rectifications,
            DocumentRelationRepository relations,
            CurrentOrganization organization,
            CurrentTerminal currentTerminal,
            DocumentOperationalEventRecorder operationalEvents,
            Clock clock) {
        this.documents = documents;
        this.rectifications = rectifications;
        this.relations = relations;
        this.organization = organization;
        this.currentTerminal = currentTerminal;
        this.operationalEvents = operationalEvents;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SalesInvoiceRectificationSourceView source(UUID originalDocumentId) {
        var original = scopedDocument(originalDocumentId);
        requireEligibleOriginal(original);
        return sourceView(original);
    }

    @Transactional(readOnly = true)
    public Details details(UUID rectificationDocumentId) {
        var metadata = metadata(rectificationDocumentId);
        var document = scopedDocument(rectificationDocumentId);
        initializePayments(document);
        var original = scopedDocument(metadata.getOriginalDocumentId());
        return new Details(document, original, metadata);
    }

    @Transactional
    public Details createDraft(
            UUID originalDocumentId,
            SalesInvoiceRectificationRequest request,
            Authentication authentication) {
        var original = lockedDocument(originalDocumentId);
        requireEligibleOriginal(original);
        var prepared = prepare(original, request, authentication);
        var draft = prepared.document();
        var metadata = prepared.metadata();

        var saved = documents.saveAndFlush(draft);
        rectifications.save(metadata);
        relations.save(new DocumentRelation(saved, original, DocumentRelationType.RECTIFICA));
        operationalEvents.record(saved, DocumentOperationalEventType.CREADO,
                saved.getCreadoPor(), saved.getTerminalOrigenId(), saved.getCreadoEn());
        return new Details(saved, original, metadata);
    }

    @Transactional(readOnly = true)
    public Details preview(
            UUID originalDocumentId,
            SalesInvoiceRectificationRequest request,
            Authentication authentication) {
        var original = scopedDocument(originalDocumentId);
        requireEligibleOriginal(original);
        return prepare(original, request, authentication);
    }

    private Details prepare(
            CommercialDocument original,
            SalesInvoiceRectificationRequest request,
            Authentication authentication) {
        Objects.requireNonNull(request, "request");
        var user = organization.currentUser(authentication);
        var draft = new CommercialDocument(
                original.getTiendaId(), original.getAlmacenId(),
                CommercialDocumentType.RECTIFICATIVA_VENTA, LocalDate.now(clock),
                user.getId(), request.reason().affectsStock()
                        ? original.getDescuentoGlobal() : BigDecimal.ZERO);
        var metadata = new SalesInvoiceRectification(
                draft.getId(), original.getId(), request.reason(), request.detail(), Instant.now(clock));
        draft.assignOriginTerminal(currentTerminalOrNull(authentication));
        draft.setParties(original.getClienteId(), null, null);
        draft.setStockOrigin(metadata.isAffectsStock());
        buildLines(draft, original, metadata, request.lines()).forEach(draft::addLine);
        validateEconomicResult(draft, metadata);
        return new Details(draft, original, metadata);
    }

    @Transactional
    public Details updateDraft(
            UUID rectificationDocumentId,
            SalesInvoiceRectificationRequest request,
            Authentication authentication) {
        Objects.requireNonNull(request, "request");
        var document = lockedDocument(rectificationDocumentId);
        var metadata = metadata(rectificationDocumentId);
        var original = lockedDocument(metadata.getOriginalDocumentId());
        requireEligibleOriginal(original);
        metadata.changeReason(request.reason(), request.detail());
        var lines = buildLines(document, original, metadata, request.lines());
        document.replaceRectificationDraft(
                globalDiscount(original, metadata), metadata.isAffectsStock(), lines);
        validateEconomicResult(document, metadata);
        var saved = documents.save(document);
        rectifications.save(metadata);
        operationalEvents.record(saved, DocumentOperationalEventType.MODIFICADO,
                organization.currentUser(authentication).getId(), currentTerminalOrNull(authentication),
                Instant.now(clock));
        return new Details(saved, original, metadata);
    }

    @Transactional
    public SalesInvoiceRectification validateBeforeConfirmation(CommercialDocument document) {
        if (document.getTipo() != CommercialDocumentType.RECTIFICATIVA_VENTA) {
            return null;
        }
        var metadata = metadata(document.getId());
        var original = lockedDocument(metadata.getOriginalDocumentId());
        requireEligibleOriginal(original);
        if (!relations.existsByDocumento_IdAndOrigen_IdAndTipo(
                document.getId(), original.getId(), DocumentRelationType.RECTIFICA)) {
            throw new IllegalStateException(
                    "La factura rectificativa no esta vinculada a su factura original");
        }
        if (metadata.isAffectsStock()) {
            validateStockAvailability(document, original, metadata);
        }
        validateEconomicResult(document, metadata);
        return metadata;
    }

    private List<DocumentLine> buildLines(
            CommercialDocument draft,
            CommercialDocument original,
            SalesInvoiceRectification metadata,
            List<SalesInvoiceRectificationRequest.LineRequest> requestedLines) {
        var requests = List.copyOf(requestedLines == null ? List.of() : requestedLines);
        if (requests.isEmpty()) {
            throw new IllegalArgumentException("Se requiere al menos una linea de rectificacion");
        }
        if (metadata.isAffectsStock()
                && original.getTipo() != CommercialDocumentType.FACTURA_VENTA) {
            throw new IllegalArgumentException(
                    "Una rectificativa previa no admite una segunda afectacion de stock");
        }
        var unique = new HashSet<UUID>();
        for (var request : requests) {
            Objects.requireNonNull(request, "line");
            if (!unique.add(request.originalLineId())) {
                throw new IllegalArgumentException("Una linea original no puede repetirse");
            }
            validQuantity(request.quantity());
            if (request.unitPrice().scale() > Money.SCALE
                    || Money.euros(request.unitPrice()).signum() < 0) {
                throw new IllegalArgumentException(
                        "El precio unitario de la diferencia debe ser positivo y tener dos decimales");
            }
        }
        var sourceLines = original.getLineas().stream()
                .collect(Collectors.toMap(DocumentLine::getId, Function.identity()));
        var result = new ArrayList<DocumentLine>();
        for (var request : requests) {
            var source = sourceLines.get(request.originalLineId());
            if (source == null) {
                throw new IllegalArgumentException(
                        "La linea seleccionada no pertenece a la factura original");
            }
            var position = result.size() + 1;
            var line = metadata.isAffectsStock()
                    ? stockLine(draft, source, request, position)
                    : economicLine(draft, source, request, position);
            line.identifyRefundOf(source.getId());
            result.add(line);
        }
        if (metadata.getReason() == SalesInvoiceRectificationReason.OPERATION_CANCELLATION) {
            requireFullCancellation(original, result);
        }
        return List.copyOf(result);
    }

    private DocumentLine stockLine(
            CommercialDocument draft,
            DocumentLine source,
            SalesInvoiceRectificationRequest.LineRequest request,
            int position) {
        if (request.quantity().signum() >= 0) {
            throw new IllegalArgumentException(
                    "Una devolucion debe reducir la cantidad original");
        }
        if (request.unitPrice().compareTo(source.getPrecioUnitario().abs()) != 0) {
            throw new IllegalArgumentException(
                    "Una devolucion conserva el precio fiscal de la linea original");
        }
        var available = availableStockQuantity(source);
        if (request.quantity().abs().compareTo(available) > 0) {
            throw new IllegalArgumentException(
                    "La cantidad supera el saldo pendiente de rectificar de " + source.getCodigo());
        }
        if (source.getLineType() == DocumentLineType.PRODUCT) {
            return new DocumentLine(
                    draft, source.getProductoId(), position,
                    request.quantity(), source.getCodigo(), source.getNombre(), source.getTarifa(),
                    source.getPrecioUnitario(), source.getDescuento(), source.isImpuestosIncluidos(),
                    source.getRegimenImpuesto(), source.getPorcentajeImpuesto());
        }
        if (request.quantity().compareTo(BigDecimal.ONE.negate()) != 0
                || available.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException(
                    "Las promociones y cupones solo admiten rectificacion completa");
        }
        return DocumentLine.special(
                draft, position, source.getNombre(),
                source.getPrecioUnitario().negate(), source.isImpuestosIncluidos(),
                source.getRegimenImpuesto(), source.getPorcentajeImpuesto(),
                source.getPromotionId(), source.getPromotionVersionId(),
                source.getPromotionalCouponId());
    }

    private static DocumentLine economicLine(
            CommercialDocument draft,
            DocumentLine source,
            SalesInvoiceRectificationRequest.LineRequest request,
            int position) {
        if (source.getLineType() == DocumentLineType.PRODUCT) {
            return new DocumentLine(
                    draft, source.getProductoId(), position,
                    request.quantity(), source.getCodigo(), source.getNombre(), "DIFERENCIA",
                    request.unitPrice(), BigDecimal.ZERO, source.isImpuestosIncluidos(),
                    source.getRegimenImpuesto(), source.getPorcentajeImpuesto());
        }
        if (request.quantity().abs().compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException(
                    "Las lineas de promocion solo admiten una diferencia unitaria");
        }
        return DocumentLine.special(
                draft, position, source.getNombre(),
                request.unitPrice().multiply(BigDecimal.valueOf(request.quantity().signum())),
                source.isImpuestosIncluidos(), source.getRegimenImpuesto(),
                source.getPorcentajeImpuesto(), source.getPromotionId(),
                source.getPromotionVersionId(), source.getPromotionalCouponId());
    }

    private void validateStockAvailability(
            CommercialDocument rectification,
            CommercialDocument original,
            SalesInvoiceRectification metadata) {
        if (original.getTipo() != CommercialDocumentType.FACTURA_VENTA) {
            throw new IllegalStateException(
                    "Una rectificativa previa no admite una segunda afectacion de stock");
        }
        var sourceLines = original.getLineas().stream()
                .collect(Collectors.toMap(DocumentLine::getId, Function.identity()));
        for (var line : rectification.getLineas()) {
            var source = sourceLines.get(line.getOriginalDocumentLineId());
            if (source == null) {
                throw new IllegalStateException(
                        "La linea rectificativa no pertenece a la factura original");
            }
            var invalidQuantity = line.getLineType() == DocumentLineType.PRODUCT
                    ? line.getCantidad().signum() >= 0
                        || line.getCantidad().abs().compareTo(availableStockQuantity(source)) > 0
                    : availableStockQuantity(source).compareTo(BigDecimal.ONE) < 0;
            if (invalidQuantity) {
                throw new IllegalStateException(
                        "La cantidad de la rectificativa ya no esta disponible");
            }
        }
        if (metadata.getReason() == SalesInvoiceRectificationReason.OPERATION_CANCELLATION) {
            requireFullCancellation(original, rectification.getLineas());
        }
    }

    private void requireFullCancellation(
            CommercialDocument original, List<DocumentLine> selectedLines) {
        var quantities = selectedLines.stream().collect(Collectors.toMap(
                DocumentLine::getOriginalDocumentLineId,
                line -> line.getCantidad().abs()));
        for (var source : original.getLineas()) {
            var available = availableStockQuantity(source);
            if (available.signum() > 0
                    && quantities.getOrDefault(source.getId(), BigDecimal.ZERO)
                    .compareTo(available) != 0) {
                throw new IllegalArgumentException(
                        "La resolucion de la operacion debe rectificar todas las lineas pendientes");
            }
        }
    }

    private void requireEligibleOriginal(CommercialDocument original) {
        if (original.getTipo() != CommercialDocumentType.FACTURA_VENTA
                && original.getTipo() != CommercialDocumentType.RECTIFICATIVA_VENTA) {
            throw new IllegalArgumentException(
                    "Solo puede rectificarse una factura de venta");
        }
        if (original.getEstado() == DocumentStatus.BORRADOR
                || original.getEstado() == DocumentStatus.ANULADO
                || original.getNumero() == null) {
            throw new IllegalStateException(
                    "La factura original debe estar confirmada y conservar su numero fiscal");
        }
        if (original.getClienteId() == null) {
            throw new IllegalStateException(
                    "La factura original no tiene cliente fiscal");
        }
    }

    private static void validateEconomicResult(
            CommercialDocument document, SalesInvoiceRectification metadata) {
        if (document.getTotal().signum() == 0) {
            throw new IllegalArgumentException(
                    "La rectificacion por diferencias no puede tener importe cero");
        }
        if (metadata.isAffectsStock() && document.getTotal().signum() >= 0) {
            throw new IllegalArgumentException(
                    "La devolucion o resolucion debe generar un importe rectificativo negativo");
        }
    }

    private SalesInvoiceRectificationSourceView sourceView(CommercialDocument original) {
        return new SalesInvoiceRectificationSourceView(
                original.getId(), original.getTipo(), original.getEstado(), original.getNumero(),
                original.getFecha(), original.getClienteId(), original.getAlmacenId(),
                original.getDescuentoGlobal(), original.getBaseTotal(), original.getImpuestoTotal(),
                original.getTotal(), original.getLineas().stream().map(line ->
                        new SalesInvoiceRectificationSourceView.LineView(
                                line.getId(), line.getLineType(), line.getCodigo(), line.getNombre(),
                                line.getCantidad(), availableStockQuantity(line),
                                line.getPrecioUnitario(), line.getDescuento(),
                                line.isImpuestosIncluidos(), line.getRegimenImpuesto(),
                                line.getPorcentajeImpuesto(), line.getBase(), line.getImpuesto(),
                                line.getTotal())).toList());
    }

    private BigDecimal availableStockQuantity(DocumentLine line) {
        var rectified = documents.confirmedRefundedQuantity(line.getId());
        return line.getCantidad().abs()
                .subtract(rectified == null ? BigDecimal.ZERO : rectified)
                .max(BigDecimal.ZERO).setScale(3, Money.ROUNDING);
    }

    private SalesInvoiceRectification metadata(UUID documentId) {
        return rectifications.findByDocumentId(Objects.requireNonNull(documentId, "documentId"))
                .orElseThrow(() -> new IllegalStateException(
                        "La factura rectificativa no tiene metadatos fiscales"));
    }

    private CommercialDocument scopedDocument(UUID id) {
        return documents.findByIdAndTiendaId(
                        Objects.requireNonNull(id, "documentId"),
                        organization.currentStore().getId())
                .orElseThrow(() -> new IllegalArgumentException("Documento no encontrado"));
    }

    private CommercialDocument lockedDocument(UUID id) {
        return documents.findLockedDocument(
                        Objects.requireNonNull(id, "documentId"),
                        organization.currentStore().getId())
                .orElseThrow(() -> new IllegalArgumentException("Documento no encontrado"));
    }

    private static BigDecimal globalDiscount(
            CommercialDocument original, SalesInvoiceRectification metadata) {
        return metadata.isAffectsStock() ? original.getDescuentoGlobal() : BigDecimal.ZERO;
    }

    private static void validQuantity(BigDecimal value) {
        if (value == null || value.signum() == 0
                || value.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException(
                    "La cantidad de diferencia debe ser distinta de cero y tener hasta tres decimales");
        }
    }

    private static void initializePayments(CommercialDocument document) {
        document.getPagos().forEach(payment -> payment.getMetodoPago().getNombre());
    }

    private UUID currentTerminalOrNull(Authentication authentication) {
        try {
            return currentTerminal.terminalId(authentication);
        } catch (IllegalStateException exception) {
            return null;
        }
    }

    public record Details(
            CommercialDocument document,
            CommercialDocument original,
            SalesInvoiceRectification metadata) {
    }
}
