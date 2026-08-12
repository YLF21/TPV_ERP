package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.shared.api.PagedResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketReportService {

    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT = 500;
    private static final EnumSet<CommercialDocumentType> TICKETS =
            EnumSet.of(CommercialDocumentType.TICKET);

    private final CommercialDocumentRepository documents;
    private final CurrentOrganization organization;
    private final CustomerRepository customers;
    private final DocumentAttributionResolver attributions;
    private final DocumentRelationRepository relations;
    private final RefundTenderRepository refundTenders;
    private final SalesInvoiceRectificationRepository invoiceRectifications;

    public TicketReportService(
            CommercialDocumentRepository documents,
            CurrentOrganization organization,
            CustomerRepository customers,
            DocumentAttributionResolver attributions,
            DocumentRelationRepository relations,
            RefundTenderRepository refundTenders,
            SalesInvoiceRectificationRepository invoiceRectifications) {
        this.documents = documents;
        this.organization = organization;
        this.customers = customers;
        this.attributions = attributions;
        this.relations = relations;
        this.refundTenders = refundTenders;
        this.invoiceRectifications = invoiceRectifications;
    }

    @Transactional(readOnly = true)
    public PagedResult<TicketReportView> list(Integer requestedLimit, String cursor) {
        var store = organization.currentStore();
        var limit = normalizedLimit(requestedLimit);
        var parsedCursor = parseCursor(cursor);
        var pageRequest = PageRequest.of(0, limit + 1);
        var values = parsedCursor.date() == null
                ? documents.findReportDocuments(store.getId(), TICKETS, pageRequest)
                : documents.findReportDocumentsAfter(
                        store.getId(), TICKETS, parsedCursor.date(),
                        parsedCursor.occurredAt(), parsedCursor.id(), pageRequest);
        var hasMore = values.size() > limit;
        var pageValues = hasMore ? new ArrayList<>(values.subList(0, limit)) : values;
        if (pageValues.isEmpty()) {
            return new PagedResult<>(List.of(), null, false);
        }

        var ticketIds = pageValues.stream().map(CommercialDocument::getId).toList();
        var customerIndex = customerIndex(pageValues);
        var attributionIndex = attributions.resolve(pageValues);
        var invoiceIndex = invoiceIndex(store.getId(), ticketIds);
        var rectificationIndex = rectificationIndex(
                store.getId(), ticketIds, invoiceIndex.values());
        var metadataIndex = rectificationMetadataIndex(rectificationIndex.values());
        var refundMethodIndex = refundMethodIndex(store.getId(), ticketIds);

        var items = pageValues.stream().map(ticket -> {
            var customer = customerIndex.get(ticket.getClienteId());
            var attribution = attributionIndex.getOrDefault(
                    ticket.getId(), DocumentAttributionResolver.Attribution.empty(ticket));
            var invoice = invoiceIndex.get(ticket.getId());
            var lifecycle = lifecycleSummary(
                    ticket, invoice, rectificationIndex, metadataIndex);
            return new TicketReportView(
                    ticket.getId(), ticket.getTipo(), ticket.getEstado(), ticket.getNumero(),
                    ticket.getFecha(), ticket.getBaseTotal(), ticket.getImpuestoTotal(),
                    ticket.getTotal(), lifecycle.effectiveTotal(),
                    ticket.getDescuentoGlobal(), ticket.getClienteId(),
                    customer == null ? "" : customer.getClientId(),
                    customer == null ? "" : Objects.toString(customer.getFiscalName(), ""),
                    attribution.userId(), attribution.userName(),
                    attribution.terminalId(), attribution.terminalName(), attribution.occurredAt(),
                    paymentMethods(ticket),
                    refundMethodIndex.getOrDefault(ticket.getId(), List.of()),
                    invoice == null ? "" : Objects.toString(invoice.getDocumentNumber(), ""),
                    lifecycle.status(), ticket.getComentarioInterno());
        }).toList();

        return new PagedResult<>(items,
                hasMore ? cursorFor(pageValues.get(pageValues.size() - 1)) : null,
                hasMore);
    }

    private Map<UUID, Customer> customerIndex(Collection<CommercialDocument> values) {
        var customerIds = values.stream()
                .map(CommercialDocument::getClienteId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        return customers.findByCompanyIdAndIdIn(
                        organization.currentCompany().getId(), customerIds)
                .stream()
                .collect(Collectors.toMap(Customer::getId, customer -> customer));
    }

    private Map<UUID, DocumentRelationRepository.RelatedDocument> invoiceIndex(
            UUID storeId, Collection<UUID> ticketIds) {
        var result = new LinkedHashMap<UUID, DocumentRelationRepository.RelatedDocument>();
        relations.findActiveRelatedDocuments(storeId, ticketIds, DocumentRelationType.FACTURA_DE)
                .stream()
                .filter(relation -> relation.getDocumentType()
                        == CommercialDocumentType.FACTURA_VENTA)
                .forEach(relation -> result.putIfAbsent(relation.getOriginId(), relation));
        return result;
    }

    private Map<UUID, List<DocumentRelationRepository.RelatedDocument>> rectificationIndex(
            UUID storeId,
            Collection<UUID> ticketIds,
            Collection<DocumentRelationRepository.RelatedDocument> invoices) {
        var sourceIds = new ArrayList<UUID>(ticketIds);
        invoices.stream().map(DocumentRelationRepository.RelatedDocument::getDocumentId)
                .forEach(sourceIds::add);
        return relations.findActiveRelatedDocuments(
                        storeId, sourceIds, DocumentRelationType.RECTIFICA)
                .stream()
                .collect(Collectors.groupingBy(
                        DocumentRelationRepository.RelatedDocument::getOriginId,
                        LinkedHashMap::new,
                        Collectors.toList()));
    }

    private Map<UUID, SalesInvoiceRectification> rectificationMetadataIndex(
            Collection<List<DocumentRelationRepository.RelatedDocument>> rectifications) {
        var documentIds = rectifications.stream()
                .flatMap(Collection::stream)
                .map(DocumentRelationRepository.RelatedDocument::getDocumentId)
                .collect(Collectors.toSet());
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        return invoiceRectifications.findByDocumentIdIn(documentIds).stream()
                .collect(Collectors.toMap(
                        SalesInvoiceRectification::getDocumentId,
                        metadata -> metadata));
    }

    private Map<UUID, List<RefundTenderType>> refundMethodIndex(
            UUID storeId, Collection<UUID> ticketIds) {
        var result = new LinkedHashMap<UUID, List<RefundTenderType>>();
        for (var tender : refundTenders.findAllByRefundDocumentIds(storeId, ticketIds)) {
            var methods = result.computeIfAbsent(
                    tender.getRefundDocument().getId(), ignored -> new ArrayList<>());
            if (!methods.contains(tender.getType())) {
                methods.add(tender.getType());
            }
        }
        return result.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> List.copyOf(entry.getValue()),
                (left, right) -> left,
                LinkedHashMap::new));
    }

    private static LifecycleSummary lifecycleSummary(
            CommercialDocument ticket,
            DocumentRelationRepository.RelatedDocument invoice,
            Map<UUID, List<DocumentRelationRepository.RelatedDocument>> rectifications,
            Map<UUID, SalesInvoiceRectification> metadata) {
        if (ticket.getEstado() == DocumentStatus.ANULADO) {
            return new LifecycleSummary(
                    TicketReportLifecycleStatus.CANCELLED, BigDecimal.ZERO);
        }
        if (ticket.getTotal().signum() < 0) {
            return new LifecycleSummary(
                    TicketReportLifecycleStatus.RETURNED, Money.euros(ticket.getTotal()));
        }

        var sourceId = invoice == null ? ticket.getId() : invoice.getDocumentId();
        var sourceTotal = invoice == null ? ticket.getTotal() : invoice.getDocumentTotal();
        var returned = rectifications.getOrDefault(sourceId, List.of()).stream()
                .filter(relation -> relation.getDocumentTotal() != null
                        && relation.getDocumentTotal().signum() < 0)
                .filter(relation -> invoice == null
                        || isStockAffectingInvoiceRectification(relation, metadata))
                .map(DocumentRelationRepository.RelatedDocument::getDocumentTotal)
                .map(BigDecimal::abs)
                .map(Money::euros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (returned.signum() > 0) {
            var status = returned.compareTo(Money.euros(sourceTotal).abs()) >= 0
                    ? TicketReportLifecycleStatus.RETURNED
                    : TicketReportLifecycleStatus.PARTIALLY_RETURNED;
            var effectiveTotal = invoice == null
                    ? Money.euros(ticket.getTotal())
                    : Money.euros(ticket.getTotal().subtract(returned)).max(BigDecimal.ZERO);
            return new LifecycleSummary(status, effectiveTotal);
        }
        return new LifecycleSummary(
                invoice == null
                        ? TicketReportLifecycleStatus.CONFIRMED
                        : TicketReportLifecycleStatus.INVOICED,
                Money.euros(ticket.getTotal()));
    }

    private static boolean isStockAffectingInvoiceRectification(
            DocumentRelationRepository.RelatedDocument relation,
            Map<UUID, SalesInvoiceRectification> metadata) {
        var value = metadata.get(relation.getDocumentId());
        return value != null && value.isAffectsStock();
    }

    private static List<String> paymentMethods(CommercialDocument ticket) {
        return ticket.getPagos().stream()
                .map(payment -> payment.getMetodoPago().getNombre())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private static int normalizedLimit(Integer requestedLimit) {
        if (requestedLimit == null || requestedLimit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }

    private static Cursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new Cursor(null, null, null);
        }
        var parts = cursor.split("\\|", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("cursor invalido");
        }
        return new Cursor(
                LocalDate.parse(parts[0]),
                Instant.parse(parts[1]),
                UUID.fromString(parts[2]).toString());
    }

    private static String cursorFor(CommercialDocument document) {
        return document.getFecha()
                + "|" + document.getOperationalOccurredAt()
                + "|" + document.getId();
    }

    private record Cursor(LocalDate date, Instant occurredAt, String id) {
    }

    private record LifecycleSummary(
            TicketReportLifecycleStatus status,
            BigDecimal effectiveTotal) {
    }
}
