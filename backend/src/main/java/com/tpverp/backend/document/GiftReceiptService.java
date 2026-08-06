package com.tpverp.backend.document;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.catalog.ProductQuantityPolicy;
import com.tpverp.backend.catalog.ProductType;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GiftReceiptService {

    private static final DateTimeFormatter CODE_DATE = DateTimeFormatter.ofPattern("yyMMdd");

    private final GiftReceiptRepository receipts;
    private final GiftReceiptLineRepository receiptLines;
    private final GiftReceiptSequenceRepository sequences;
    private final DocumentService documents;
    private final CurrentOrganization organization;
    private final CurrentTerminal currentTerminal;
    private final AuditService audit;
    private final Clock clock;

    public GiftReceiptService(
            GiftReceiptRepository receipts,
            GiftReceiptLineRepository receiptLines,
            GiftReceiptSequenceRepository sequences,
            DocumentService documents,
            CurrentOrganization organization,
            CurrentTerminal currentTerminal,
            AuditService audit,
            Clock clock) {
        this.receipts = receipts;
        this.receiptLines = receiptLines;
        this.sequences = sequences;
        this.documents = documents;
        this.organization = organization;
        this.currentTerminal = currentTerminal;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Preview preview(String ticketNumber) {
        var ticket = documents.ticketForReturnByNumber(required(ticketNumber, "ticketNumber"));
        return preview(ticket);
    }

    @Transactional
    public View issue(
            UUID requestId,
            String ticketNumber,
            List<LineSelection> selections,
            Authentication authentication) {
        Objects.requireNonNull(requestId, "requestId");
        var store = organization.currentStore();
        var prior = receipts.findByStoreIdAndRequestId(store.getId(), requestId);
        if (prior.isPresent()) {
            var existing = prior.orElseThrow();
            var requestedTicket = documents.ticketForReturnByNumber(
                    required(ticketNumber, "ticketNumber"));
            var normalized = validateSelections(requestedTicket, selections);
            requireSameRequest(existing, requestedTicket.getId(), normalized);
            return view(existing, requestedTicket);
        }

        var ticket = documents.ticketForReturnByNumber(required(ticketNumber, "ticketNumber"));
        var normalized = validateSelections(ticket, selections);
        var now = Instant.now(clock);
        var date = LocalDate.ofInstant(now, ZoneId.of(store.getTimezone()));
        var sequence = sequences.next(store.getId(), date);
        var code = "RG-%s-%s-%05d".formatted(
                store.getCodigoTienda(), CODE_DATE.format(date), sequence);
        var user = organization.currentUser(authentication);
        var receipt = new GiftReceipt(
                store.getId(),
                ticket.getId(),
                requestId,
                code,
                user.getId(),
                currentTerminal.terminalId(authentication),
                now);
        normalized.forEach(line -> receipt.addLine(
                line.lineId(), line.quantity(), line.serialNumbers()));
        var saved = receipts.save(receipt);
        audit.record(
                "GIFT_RECEIPT_ISSUED",
                AuditResult.EXITO,
                Map.of(
                        "giftReceiptId", saved.getId().toString(),
                        "giftReceiptCode", saved.getCode(),
                        "sourceDocumentId", ticket.getId().toString(),
                        "sourceDocumentNumber", ticket.getNumero(),
                        "operatorId", user.getId().toString(),
                        "operatorUsername", user.getUserName(),
                        "lineCount", saved.getLines().size()));
        return view(saved, ticket);
    }

    @Transactional(readOnly = true)
    public View findByCode(String code) {
        var receipt = requiredReceipt(code);
        return view(receipt, documents.loadForPrint(receipt.getSourceDocumentId()));
    }

    @Transactional(readOnly = true)
    public GiftReturnContext returnContext(String code) {
        var receipt = requiredReceipt(code);
        return returnContext(receipt);
    }

    @Transactional(readOnly = true)
    public Optional<GiftReturnContext> findReturnContext(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return receipts.findByStoreIdAndCodigoIgnoreCase(
                        organization.currentStore().getId(), code.trim())
                .map(this::returnContext);
    }

    private GiftReturnContext returnContext(GiftReceipt receipt) {
        var ticket = documents.ticketForReturnByNumber(
                documents.loadForPrint(receipt.getSourceDocumentId()).getNumero());
        var sourceOptions = documents.cardRefundLineOptions(ticket.getId()).stream()
                .collect(Collectors.toMap(
                        DocumentService.CardRefundLineOption::lineId,
                        Function.identity()));
        var options = new ArrayList<GiftReturnLine>();
        for (var receiptLine : receipt.getLines()) {
            var source = sourceOptions.get(receiptLine.getSourceDocumentLineId());
            if (source == null) {
                continue;
            }
            var alreadyReturned = receiptLines.confirmedReturnedQuantity(receiptLine.getId());
            var receiptAvailable = receiptLine.getQuantity()
                    .subtract(alreadyReturned == null ? BigDecimal.ZERO : alreadyReturned)
                    .max(BigDecimal.ZERO)
                    .setScale(3, Money.ROUNDING);
            var available = receiptAvailable.min(source.refundableQuantity());
            var serials = receiptLine.getSerialNumbers().isEmpty()
                    ? source.refundableSerialNumbers()
                    : receiptLine.getSerialNumbers().stream()
                            .filter(source.refundableSerialNumbers()::contains)
                            .toList();
            if (!receiptLine.getSerialNumbers().isEmpty()) {
                available = available.min(BigDecimal.valueOf(serials.size())
                        .setScale(3, Money.ROUNDING));
            }
            if (available.signum() <= 0) {
                continue;
            }
            var total = source.refundableQuantity().signum() == 0
                    ? BigDecimal.ZERO
                    : Money.euros(source.refundableTotal()
                            .multiply(available)
                            .divide(source.refundableQuantity(), Money.SCALE + 4, Money.ROUNDING));
            options.add(new GiftReturnLine(
                    receiptLine.getId(),
                    source.lineId(),
                    source.productId(),
                    source.code(),
                    source.barcode(),
                    source.barcode2(),
                    source.name(),
                    available,
                    source.unitPrice(),
                    total,
                    serials,
                    source.discount(),
                    source.taxesIncluded(),
                    source.taxRegime(),
                    source.taxPercentage(),
                    source.productType()));
        }
        return new GiftReturnContext(receipt, ticket, List.copyOf(options));
    }

    private Preview preview(CommercialDocument ticket) {
        var options = documents.cardRefundLineOptions(ticket.getId()).stream()
                .filter(option -> option.lineType() == DocumentLineType.PRODUCT)
                .map(option -> new PreviewLine(
                        option.lineId(),
                        option.code(),
                        option.name(),
                        option.refundableQuantity(),
                        option.refundableSerialNumbers(),
                        option.productType()))
                .toList();
        return new Preview(
                ticket.getId(), ticket.getNumero(), ticket.getConfirmadoEn(), options);
    }

    private List<LineSelection> validateSelections(
            CommercialDocument ticket,
            List<LineSelection> requested) {
        var values = List.copyOf(requested == null ? List.of() : requested);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("gift_receipt_lines_required");
        }
        var options = documents.cardRefundLineOptions(ticket.getId()).stream()
                .filter(option -> option.lineType() == DocumentLineType.PRODUCT)
                .collect(Collectors.toMap(
                        DocumentService.CardRefundLineOption::lineId,
                        Function.identity()));
        var seen = new HashSet<UUID>();
        var result = new ArrayList<LineSelection>();
        for (var requestedLine : values) {
            Objects.requireNonNull(requestedLine, "line");
            if (!seen.add(requestedLine.lineId())) {
                throw new IllegalArgumentException("gift_receipt_line_duplicated");
            }
            var option = options.get(requestedLine.lineId());
            if (option == null) {
                throw new IllegalArgumentException("gift_receipt_line_not_available");
            }
            var requestedQuantity = Objects.requireNonNull(
                    requestedLine.quantity(), "quantity");
            ProductQuantityPolicy.requireValid(option.productType(), requestedQuantity);
            var quantity = requestedQuantity.setScale(3, Money.ROUNDING);
            if (quantity.signum() <= 0 || quantity.compareTo(option.refundableQuantity()) > 0) {
                throw new IllegalArgumentException("gift_receipt_quantity_not_available");
            }
            var serials = normalizedSerials(requestedLine.serialNumbers());
            if (option.refundableSerialNumbers().isEmpty()) {
                if (!serials.isEmpty()) {
                    throw new IllegalArgumentException("gift_receipt_serial_not_allowed");
                }
            } else {
                if (quantity.stripTrailingZeros().scale() > 0
                        || quantity.intValueExact() != serials.size()
                        || !option.refundableSerialNumbers().containsAll(serials)) {
                    throw new IllegalArgumentException("gift_receipt_serial_selection_invalid");
                }
            }
            result.add(new LineSelection(option.lineId(), quantity, serials));
        }
        return List.copyOf(result);
    }

    private GiftReceipt requiredReceipt(String code) {
        return receipts.findByStoreIdAndCodigoIgnoreCase(
                        organization.currentStore().getId(), required(code, "code"))
                .orElseThrow(() -> new IllegalArgumentException(
                        "gift_receipt_not_found"));
    }

    private void requireSameRequest(
            GiftReceipt receipt,
            UUID ticketId,
            List<LineSelection> requested) {
        var existing = receipt.getLines().stream()
                .map(line -> new LineSelection(
                        line.getSourceDocumentLineId(),
                        line.getQuantity(),
                        line.getSerialNumbers()))
                .toList();
        if (!receipt.getSourceDocumentId().equals(ticketId) || !existing.equals(requested)) {
            throw new IllegalStateException("gift_receipt_idempotency_conflict");
        }
    }

    private View view(GiftReceipt receipt, CommercialDocument ticket) {
        var sourceLines = ticket.getLineas().stream().collect(Collectors.toMap(
                DocumentLine::getId, Function.identity()));
        return new View(
                receipt.getId(),
                receipt.getCode(),
                receipt.getCreatedAt(),
                ticket.getId(),
                ticket.getNumero(),
                receipt.getLines().stream().map(line -> {
                    var source = sourceLines.get(line.getSourceDocumentLineId());
                    if (source == null) {
                        throw new IllegalStateException("gift_receipt_source_line_missing");
                    }
                    return new ViewLine(
                            line.getId(),
                            source.getId(),
                            source.getCodigo(),
                            source.getNombre(),
                            line.getQuantity(),
                            line.getSerialNumbers());
                }).toList());
    }

    private static List<String> normalizedSerials(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<String>();
        var seen = new HashSet<String>();
        for (var value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("gift_receipt_serial_number_required");
            }
            var normalized = value.trim();
            if (!seen.add(normalized.toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("gift_receipt_serial_number_duplicated");
            }
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }

    public record LineSelection(UUID lineId, BigDecimal quantity, List<String> serialNumbers) {
        public LineSelection {
            serialNumbers = serialNumbers == null ? List.of() : List.copyOf(serialNumbers);
        }
    }

    public record Preview(
            UUID ticketId,
            String ticketNumber,
            Instant issuedAt,
            List<PreviewLine> lines) {
    }

    public record PreviewLine(
            UUID lineId,
            String code,
            String name,
            BigDecimal availableQuantity,
            List<String> serialNumbers,
            ProductType productType) {
    }

    public record View(
            UUID id,
            String code,
            Instant issuedAt,
            UUID sourceTicketId,
            String sourceTicketNumber,
            List<ViewLine> lines) {
    }

    public record ViewLine(
            UUID giftReceiptLineId,
            UUID sourceLineId,
            String code,
            String name,
            BigDecimal quantity,
            List<String> serialNumbers) {
    }

    public record GiftReturnContext(
            GiftReceipt receipt,
            CommercialDocument ticket,
            List<GiftReturnLine> lines) {
    }

    public record GiftReturnLine(
            UUID giftReceiptLineId,
            UUID sourceLineId,
            UUID productId,
            String code,
            String barcode,
            String barcode2,
            String name,
            BigDecimal refundableQuantity,
            BigDecimal unitPrice,
            BigDecimal refundableTotal,
            List<String> serialNumbers,
            BigDecimal discount,
            boolean taxesIncluded,
            String taxRegime,
            BigDecimal taxPercentage,
            ProductType productType) {
    }
}
