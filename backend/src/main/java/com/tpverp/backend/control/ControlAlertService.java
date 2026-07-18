package com.tpverp.backend.control;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.document.DocumentLine;
import com.tpverp.backend.organization.CurrentOrganization;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ControlAlertService {

    private final ControlAlertRepository alerts;
    private final ControlRuleRepository rules;
    private final ControlAlertHistoryRepository history;
    private final CommercialDocumentRepository documents;
    private final CurrentOrganization organization;
    private final Clock clock;

    public ControlAlertService(
            ControlAlertRepository alerts,
            ControlRuleRepository rules,
            ControlAlertHistoryRepository history,
            CommercialDocumentRepository documents,
            CurrentOrganization organization,
            Clock clock) {
        this.alerts = alerts;
        this.rules = rules;
        this.history = history;
        this.documents = documents;
        this.organization = organization;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<AlertSummaryView> list(
            ControlAlertStatus status,
            ControlAlertType type,
            UUID ruleId,
            Instant from,
            Instant to,
            String search,
            int page,
            int size) {
        if (page < 0) throw new IllegalArgumentException("page no puede ser negativo");
        if (size < 1 || size > 100) throw new IllegalArgumentException("size debe estar entre 1 y 100");
        validateRange(from, to);
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        var normalizedSearch = search == null || search.isBlank() ? null : search.trim();
        if (normalizedSearch != null && normalizedSearch.length() > 160) {
            throw new IllegalArgumentException("search no puede superar 160 caracteres");
        }
        return alerts.findAll(filter(
                        organization.currentStore().getId(), status, type, ruleId, from, to,
                        normalizedSearch), pageable)
                .map(ControlAlertService::summary);
    }

    @Transactional(readOnly = true)
    public List<RuleAlertCountView> countsByRule(Instant from, Instant to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from y to son obligatorios para agrupar alertas");
        }
        validateRange(from, to);
        var storeId = organization.currentStore().getId();
        var counts = new java.util.HashMap<UUID, MutableRuleCounts>();
        for (var item : alerts.countByRuleAndStatus(storeId, from, to)) {
            counts.computeIfAbsent(item.getRuleId(), ignored -> new MutableRuleCounts())
                    .add(item.getStatus(), item.getTotal());
        }
        return rules.findAllByStoreIdOrderByTypeAsc(storeId).stream()
                .map(rule -> counts.getOrDefault(rule.getId(), new MutableRuleCounts()).view(rule))
                .toList();
    }

    @Transactional(readOnly = true)
    public AlertDashboardSummaryView dashboardSummary() {
        var storeId = organization.currentStore().getId();
        long newCount = 0;
        long reviewedCount = 0;
        for (var count : alerts.countByStoreIdGroupedByStatus(storeId)) {
            if (count.getStatus() == ControlAlertStatus.NEW) {
                newCount = count.getTotal();
            } else if (count.getStatus() == ControlAlertStatus.REVIEWED) {
                reviewedCount = count.getTotal();
            }
        }
        var recent = alerts.findAllByStoreId(
                        storeId,
                        PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "createdAt", "id")))
                .stream()
                .map(ControlAlertService::summary)
                .toList();
        return new AlertDashboardSummaryView(newCount, reviewedCount, recent);
    }

    private static Specification<ControlAlert> filter(
            UUID storeId,
            ControlAlertStatus status,
            ControlAlertType type,
            UUID ruleId,
            Instant from,
            Instant to,
            String search) {
        return (root, query, builder) -> {
            var predicates = new java.util.ArrayList<Predicate>();
            predicates.add(builder.equal(root.get("storeId"), storeId));
            if (status != null) predicates.add(builder.equal(root.get("status"), status));
            var event = root.join("event");
            if (type != null) predicates.add(builder.equal(event.get("type"), type));
            if (ruleId != null) predicates.add(builder.equal(event.get("ruleId"), ruleId));
            if (from != null) predicates.add(builder.greaterThanOrEqualTo(event.get("occurredAt"), from));
            if (to != null) predicates.add(builder.lessThan(event.get("occurredAt"), to));
            if (search != null) {
                var pattern = "%" + search.toLowerCase(java.util.Locale.ROOT) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(event.get("ruleName")), pattern),
                        builder.like(builder.lower(event.get("userName")), pattern),
                        builder.like(builder.lower(event.get("documentNumber")), pattern)));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static void validateRange(Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("from debe ser anterior a to");
        }
    }

    @Transactional(readOnly = true)
    public AlertDetailView get(UUID id) {
        var alert = find(id);
        return detail(alert, history.findAllByAlertIdOrderByChangedAtAsc(alert.getId()));
    }

    @Transactional
    public AlertDetailView transition(
            UUID id,
            ControlAlertStatus next,
            TransitionRequest request,
            Authentication authentication) {
        if (next == ControlAlertStatus.NEW) throw new IllegalArgumentException("No se puede volver al estado NEW");
        var alert = find(id);
        requireVersion(alert, request.version());
        var user = organization.currentUser(authentication);
        var now = clock.instant();
        var previous = alert.transition(next, now);
        try {
            alerts.saveAndFlush(alert);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException exception) {
            throw staleVersion(alert.getId(), request.version(), null);
        }
        history.save(new ControlAlertHistory(
                alert, previous, next, request.comment(), user.getId(), now));
        return detail(alert, history.findAllByAlertIdOrderByChangedAtAsc(alert.getId()));
    }

    @Transactional(readOnly = true)
    public RelatedDocumentView relatedDocument(UUID alertId) {
        var event = find(alertId).getEvent();
        if (event.getDocumentId() == null) {
            throw new NoSuchElementException("La alerta no tiene un documento relacionado");
        }
        var document = documents.findByIdAndTiendaId(
                        event.getDocumentId(), organization.currentStore().getId())
                .orElseThrow(() -> new NoSuchElementException("Documento relacionado no encontrado"));
        return documentView(document);
    }

    private ControlAlert find(UUID id) {
        return alerts.findByIdAndStoreId(id, organization.currentStore().getId())
                .orElseThrow(() -> new NoSuchElementException("Alerta de control no encontrada"));
    }

    private static void requireVersion(ControlAlert alert, Long expected) {
        if (expected == null) throw new IllegalArgumentException("version es obligatoria");
        if (alert.getVersion() != expected) throw staleVersion(alert.getId(), expected, alert.getVersion());
    }

    private static IllegalStateException staleVersion(UUID id, long expected, Long actual) {
        var detail = actual == null ? "ya fue modificada" : "tiene version " + actual;
        return new IllegalStateException(
                "Conflicto de version en la alerta " + id + ": se esperaba " + expected + " y " + detail);
    }

    private static AlertSummaryView summary(ControlAlert alert) {
        var event = alert.getEvent();
        return new AlertSummaryView(
                alert.getId(), alert.getStatus(), event.getType(), event.getRuleId(),
                event.getRuleVersion(), event.getRuleName(), event.getDocumentId(),
                event.getDocumentNumber(), event.getTerminalId(),
                event.getUserId(), event.getUserName(), event.getOccurredAt(),
                event.getData(), alert.getUpdatedAt(), alert.getVersion());
    }

    private static AlertDetailView detail(
            ControlAlert alert, List<ControlAlertHistory> history) {
        return new AlertDetailView(
                summary(alert),
                history.stream().map(item -> new HistoryView(
                        item.getPreviousStatus(), item.getNewStatus(), item.getComment(),
                        item.getChangedBy(), item.getChangedAt())).toList());
    }

    private static RelatedDocumentView documentView(CommercialDocument document) {
        return new RelatedDocumentView(
                document.getId(), document.getTipo().name(), document.getEstado().name(),
                document.getNumero(), document.getFecha(), document.getClienteId(),
                document.getProveedorId(), document.getDescuentoGlobal(), document.getBaseTotal(),
                document.getImpuestoTotal(), document.getTotal(), document.getMoneda(),
                document.getMotivoAnulacion(), document.getLineas().stream()
                .map(ControlAlertService::lineView).toList(), document.getPagos().stream()
                .map(payment -> new RelatedDocumentPaymentView(
                        payment.getPosicion(), payment.getMetodoPago().getId(),
                        payment.getMetodoPago().getNombre(), payment.getImporte(),
                        payment.isPrincipal(), payment.getEntregado(), payment.getCambio(),
                        payment.getReferencia(), payment.getCardMode() == null ? null : payment.getCardMode().name(),
                        payment.getPaymentTerminalStatus() == null
                                ? null : payment.getPaymentTerminalStatus().name()))
                .toList());
    }

    private static RelatedDocumentLineView lineView(DocumentLine line) {
        return new RelatedDocumentLineView(
                line.getPosicion(), line.getLineType().name(), line.getProductoId(),
                line.getCodigo(), line.getNombre(), line.getCantidad(), line.getPrecioUnitario(),
                line.getDescuento(), line.isImpuestosIncluidos(), line.getRegimenImpuesto(),
                line.getPorcentajeImpuesto(), line.getBase(), line.getImpuesto(), line.getTotal());
    }

    public record TransitionRequest(
            @NotNull Long version,
            @Size(max = 500) String comment) {
    }

    public record AlertSummaryView(
            UUID id,
            ControlAlertStatus status,
            ControlAlertType type,
            UUID ruleId,
            int ruleVersion,
            String ruleName,
            UUID documentId,
            String documentNumber,
            UUID terminalId,
            UUID userId,
            String userName,
            Instant occurredAt,
            Map<String, Object> data,
            Instant updatedAt,
            long version) {
    }

    public record AlertDashboardSummaryView(
            long newCount,
            long reviewedCount,
            List<AlertSummaryView> recentAlerts) {

        public AlertDashboardSummaryView {
            recentAlerts = List.copyOf(recentAlerts);
        }
    }

    public record RuleAlertCountView(
            UUID ruleId,
            ControlAlertType type,
            String ruleName,
            ControlRuleParameterKind parameterKind,
            Map<String, Object> configuration,
            boolean supported,
            boolean active,
            long total,
            long newCount,
            long reviewedCount,
            long closedCount,
            long dismissedCount) {
    }

    private static final class MutableRuleCounts {
        private long newCount;
        private long reviewedCount;
        private long closedCount;
        private long dismissedCount;

        void add(ControlAlertStatus status, long value) {
            switch (status) {
                case NEW -> newCount += value;
                case REVIEWED -> reviewedCount += value;
                case CLOSED -> closedCount += value;
                case DISMISSED -> dismissedCount += value;
            }
        }

        RuleAlertCountView view(ControlRule rule) {
            return new RuleAlertCountView(
                    rule.getId(), rule.getType(), rule.getName(), rule.getType().parameterKind(),
                    rule.getConfiguration(), rule.getType().supported(), rule.isActive(),
                    newCount + reviewedCount + closedCount + dismissedCount,
                    newCount, reviewedCount, closedCount, dismissedCount);
        }
    }

    public record AlertDetailView(AlertSummaryView alert, List<HistoryView> history) {
    }

    public record HistoryView(
            ControlAlertStatus previousStatus,
            ControlAlertStatus newStatus,
            String comment,
            UUID changedBy,
            Instant changedAt) {
    }

    public record RelatedDocumentView(
            UUID id,
            String type,
            String status,
            String number,
            LocalDate date,
            UUID customerId,
            UUID supplierId,
            BigDecimal globalDiscount,
            BigDecimal baseTotal,
            BigDecimal taxTotal,
            BigDecimal total,
            String currency,
            String cancellationReason,
            List<RelatedDocumentLineView> lines,
            List<RelatedDocumentPaymentView> payments) {
    }

    public record RelatedDocumentLineView(
            int position,
            String lineType,
            UUID productId,
            String code,
            String name,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discount,
            boolean taxesIncluded,
            String taxRegime,
            BigDecimal taxPercent,
            BigDecimal base,
            BigDecimal tax,
            BigDecimal total) {
    }

    public record RelatedDocumentPaymentView(
            int position,
            UUID paymentMethodId,
            String paymentMethod,
            BigDecimal amount,
            boolean principal,
            BigDecimal tendered,
            BigDecimal change,
            String reference,
            String cardMode,
            String terminalStatus) {
    }
}
