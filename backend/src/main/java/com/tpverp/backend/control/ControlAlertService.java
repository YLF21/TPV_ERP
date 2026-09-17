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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
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
    private final ControlAlertWorkHistoryRepository workHistory;
    private final CommercialDocumentRepository documents;
    private final ControlAlertReadRepository read;
    private final CurrentOrganization organization;
    private final Clock clock;

    public ControlAlertService(
            ControlAlertRepository alerts,
            ControlRuleRepository rules,
            ControlAlertHistoryRepository history,
            ControlAlertWorkHistoryRepository workHistory,
            CommercialDocumentRepository documents,
            ControlAlertReadRepository read,
            CurrentOrganization organization,
            Clock clock) {
        this.alerts = alerts;
        this.rules = rules;
        this.history = history;
        this.workHistory = workHistory;
        this.documents = documents;
        this.read = read;
        this.organization = organization;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<AlertSummaryView> list(
            ControlAlertStatus status,
            ControlAlertType type,
            UUID ruleId,
            ControlAlertPriority priority,
            UUID assigneeId,
            Boolean overdue,
            Instant from,
            Instant to,
            String search,
            int page,
            int size,
            String sortBy,
            String sortDirection) {
        if (page < 0) throw new IllegalArgumentException("page no puede ser negativo");
        if (size < 1 || size > 100) throw new IllegalArgumentException("size debe estar entre 1 y 100");
        validateRange(from, to);
        var pageable = PageRequest.of(page, size, alertSort(sortBy, sortDirection));
        var normalizedSearch = normalizedSearch(search);
        var result = alerts.findAll(filter(
                        organization.currentStore().getId(), status, type, ruleId, priority,
                        assigneeId, overdue, clock.instant(), from, to, normalizedSearch), pageable);
        var views = summaries(result.getContent());
        return result.map(alert -> views.get(alert.getId()));
    }

    private static Sort alertSort(String sortBy, String sortDirection) {
        if (sortBy == null || sortBy.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "event.occurredAt", "id");
        }
        var property = switch (sortBy) {
            case "occurredAt" -> "event.occurredAt";
            case "username" -> "event.userName";
            case "terminal" -> "event.terminalId";
            case "document" -> "event.documentNumber";
            case "detail" -> "event.type";
            case "status" -> "status";
            default -> throw new IllegalArgumentException("sortBy no es valido");
        };
        var direction = "desc".equalsIgnoreCase(sortDirection) ? Sort.Direction.DESC : Sort.Direction.ASC;
        return Sort.by(direction, property).and(Sort.by(direction, "id"));
    }

    @Transactional(readOnly = true)
    public List<RuleAlertCountView> countsByRule(Instant from, Instant to) {
        return countsByRule(from, to, null, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<RuleAlertCountView> countsByRule(
            Instant from, Instant to, ControlAlertStatus status,
            ControlAlertPriority priority, UUID assigneeId, Boolean overdue, String search) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from y to son obligatorios para agrupar alertas");
        }
        validateRange(from, to);
        var storeId = organization.currentStore().getId();
        var counts = new java.util.HashMap<UUID, MutableRuleCounts>();
        var normalizedSearch = normalizedSearch(search);
        var filtered = status != null || priority != null || assigneeId != null
                || Boolean.TRUE.equals(overdue) || normalizedSearch != null;
        var groupedCounts = filtered
                ? alerts.countByRuleAndStatusFiltered(storeId, from, to, status, priority,
                        assigneeId, Boolean.TRUE.equals(overdue), clock.instant(),
                        normalizedSearch == null ? null
                                : "%" + normalizedSearch.toLowerCase(java.util.Locale.ROOT) + "%")
                : alerts.countByRuleAndStatus(storeId, from, to);
        for (var item : groupedCounts) {
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
        var recentAlerts = alerts.findAllByStoreId(
                        storeId,
                        PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "event.occurredAt", "id")));
        var views = summaries(recentAlerts);
        var recent = recentAlerts.stream().map(alert -> views.get(alert.getId())).toList();
        return new AlertDashboardSummaryView(newCount, reviewedCount, recent);
    }

    private static Specification<ControlAlert> filter(
            UUID storeId,
            ControlAlertStatus status,
            ControlAlertType type,
            UUID ruleId,
            ControlAlertPriority priority,
            UUID assigneeId,
            Boolean overdue,
            Instant now,
            Instant from,
            Instant to,
            String search) {
        return (root, query, builder) -> {
            var predicates = new java.util.ArrayList<Predicate>();
            predicates.add(builder.equal(root.get("storeId"), storeId));
            if (status != null) predicates.add(builder.equal(root.get("status"), status));
            if (priority != null) predicates.add(builder.equal(root.get("priority"), priority));
            if (assigneeId != null) predicates.add(builder.equal(root.get("assigneeId"), assigneeId));
            if (Boolean.TRUE.equals(overdue)) {
                predicates.add(root.get("status").in(ControlAlertStatus.NEW, ControlAlertStatus.REVIEWED));
                predicates.add(builder.isNotNull(root.get("dueAt")));
                predicates.add(builder.lessThan(root.get("dueAt"), now));
            }
            var event = root.join("event");
            // Scope both sides so the event's store/date index can serve chronological pages.
            predicates.add(builder.equal(event.get("storeId"), storeId));
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

    private static String normalizedSearch(String search) {
        var value = search == null || search.isBlank() ? null : search.trim();
        if (value != null && value.length() > 160) {
            throw new IllegalArgumentException("search no puede superar 160 caracteres");
        }
        return value;
    }

    @Transactional(readOnly = true)
    public AlertDetailView get(UUID id) {
        var alert = find(id);
        return detail(alert);
    }

    @Transactional
    public AlertDetailView transition(
            UUID id,
            ControlAlertStatus next,
            TransitionRequest request,
            Authentication authentication) {
        if (next == ControlAlertStatus.NEW) throw new IllegalArgumentException("No se puede volver al estado NEW");
        return changeStatus(id, next, request, authentication);
    }

    @Transactional
    public AlertDetailView reopen(UUID id, TransitionRequest request, Authentication authentication) {
        return changeStatus(id, ControlAlertStatus.NEW, request, authentication);
    }

    private AlertDetailView changeStatus(UUID id, ControlAlertStatus next,
            TransitionRequest request, Authentication authentication) {
        var alert = find(id);
        requireVersion(alert, request.version());
        var user = organization.currentUser(authentication);
        var now = clock.instant();
        var previous = next == ControlAlertStatus.NEW ? alert.reopen(now) : alert.transition(next, now);
        try {
            alerts.saveAndFlush(alert);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException exception) {
            throw staleVersion(alert.getId(), request.version(), null);
        }
        history.save(new ControlAlertHistory(
                alert, previous, next, request.comment(), user.getId(), now));
        return detail(alert);
    }

    @Transactional(readOnly = true)
    public List<AssigneeOptionView> assigneeOptions() {
        return read.eligibleAssignees(organization.currentStore().getId(), null).stream()
                .map(user -> new AssigneeOptionView(user.getUserId(), user.getName(), user.getUserName()))
                .toList();
    }

    @Transactional
    public AlertDetailView updateWork(
            UUID id,
            WorkUpdateRequest request,
            Authentication authentication) {
        var alert = find(id);
        requireVersion(alert, request.version());
        var store = organization.currentStore();
        if (request.dueAt() != null && request.dueAt().isBefore(alert.getCreatedAt())) {
            throw new IllegalArgumentException("El vencimiento no puede ser anterior a la alerta");
        }
        if (request.assigneeId() != null
                && read.eligibleAssignees(store.getId(), request.assigneeId()).isEmpty()) {
            throw new IllegalArgumentException("message.control.alert_assignee_not_eligible");
        }
        var actor = organization.currentUser(authentication);
        var now = clock.instant();
        var previous = alert.updateWork(
                request.priority(), request.assigneeId(), request.dueAt(), now);
        try {
            alerts.saveAndFlush(alert);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException exception) {
            throw staleVersion(alert.getId(), request.version(), null);
        }
        workHistory.save(new ControlAlertWorkHistory(
                alert, previous, request.comment(), actor.getId(), now));
        return detail(alert);
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
        return documentView(document, read.customerName(organization.currentStore().getId(), document.getId()));
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

    private Map<UUID, AlertSummaryView> summaries(List<ControlAlert> page) {
        if (page.isEmpty()) return Map.of();
        var storeId = organization.currentStore().getId();
        var ids = page.stream().map(ControlAlert::getId).toList();
        var labels = new HashMap<UUID, ControlAlertReadRepository.SummaryLabels>();
        read.summaryLabels(storeId, ids).forEach(item -> labels.put(item.getAlertId(), item));
        var lineLabels = new HashMap<UUID, Map<LineIdentity, ControlAlertReadRepository.EvidenceLineLabels>>();
        var evidenceIds = page.stream().filter(alert -> EVIDENCE_LINE_KEYS.stream()
                        .anyMatch(key -> alert.getEvent().getData().get(key) instanceof List<?> lines
                                && !lines.isEmpty()))
                .map(ControlAlert::getId).toList();
        if (!evidenceIds.isEmpty()) {
            read.evidenceLineLabels(storeId, evidenceIds).forEach(item -> lineLabels
                    .computeIfAbsent(item.getAlertId(), ignored -> new HashMap<>())
                    .put(new LineIdentity(Integer.toString(item.getPosition()), item.getProductId().toString()), item));
        }
        var result = new HashMap<UUID, AlertSummaryView>();
        for (var alert : page) {
            result.put(alert.getId(), summary(alert, labels.get(alert.getId()),
                    lineLabels.getOrDefault(alert.getId(), Map.of())));
        }
        return result;
    }

    private static final List<String> EVIDENCE_LINE_KEYS = List.of("changedLines", "discountedLines", "matchingLines");
    private record LineIdentity(String position, String productId) {}

    private static Map<String, Object> evidenceData(Map<String, Object> data,
            Map<LineIdentity, ControlAlertReadRepository.EvidenceLineLabels> labels) {
        if (labels.isEmpty()) return data;
        var result = new LinkedHashMap<>(data);
        for (var key : EVIDENCE_LINE_KEYS) {
            if (!(data.get(key) instanceof List<?> lines)) continue;
            result.put(key, lines.stream().map(value -> {
                if (!(value instanceof Map<?, ?> line)) return value;
                var label = labels.get(new LineIdentity(
                        String.valueOf(line.get("position")), String.valueOf(line.get("productId"))));
                if (label == null) return value;
                var enriched = new LinkedHashMap<Object, Object>(line);
                // Presentation only: keep original evidence and amounts, including stored labels.
                enriched.putIfAbsent("name", label.getName());
                enriched.putIfAbsent("code", label.getCode());
                return enriched;
            }).toList());
        }
        return result;
    }

    private static AlertSummaryView summary(ControlAlert alert,
            ControlAlertReadRepository.SummaryLabels labels,
            Map<LineIdentity, ControlAlertReadRepository.EvidenceLineLabels> lineLabels) {
        var event = alert.getEvent();
        return new AlertSummaryView(
                alert.getId(), alert.getStatus(), event.getType(), event.getRuleId(),
                event.getRuleVersion(), event.getRuleName(), event.getDocumentId(),
                event.getDocumentNumber(), event.getTerminalId(),
                event.getUserId(), event.getUserName(), event.getOccurredAt(),
                evidenceData(event.getData(), lineLabels), alert.getPriority(), alert.getAssigneeId(), alert.getDueAt(),
                alert.getUpdatedAt(), alert.getVersion(), labels == null ? null : labels.getTerminalName(),
                labels == null ? null : labels.getReviewComment(), labels == null ? null : labels.getAssigneeName());
    }

    private AlertDetailView detail(ControlAlert alert) {
        var storeId = organization.currentStore().getId();
        var transitions = history.findAllByAlertIdAndStoreIdOrderByChangedAtAscIdAsc(alert.getId(), storeId);
        var work = workHistory.findAllByAlertIdAndStoreIdOrderByChangedAtAscIdAsc(alert.getId(), storeId);
        var userIds = new HashSet<UUID>();
        transitions.forEach(item -> userIds.add(item.getChangedBy()));
        work.forEach(item -> {
            userIds.add(item.getChangedBy());
            userIds.add(item.getPreviousAssigneeId());
            userIds.add(item.getNewAssigneeId());
        });
        userIds.remove(null);
        var names = new HashMap<UUID, String>();
        var ids = List.copyOf(userIds);
        for (int start = 0; start < ids.size(); start += 100) {
            read.userLabels(storeId, ids.subList(start, Math.min(start + 100, ids.size())))
                    .forEach(item -> names.put(item.getUserId(), item.getName()));
        }
        return new AlertDetailView(
                summaries(List.of(alert)).get(alert.getId()),
                transitions.stream()
                        .map(item -> new HistoryView(
                        item.getPreviousStatus(), item.getNewStatus(), item.getComment(),
                        item.getChangedBy(), item.getChangedAt(), names.get(item.getChangedBy()))).toList(),
                work.stream()
                        .map(item -> new WorkHistoryView(
                                item.getPreviousPriority(), item.getNewPriority(),
                                item.getPreviousAssigneeId(), item.getNewAssigneeId(),
                                item.getPreviousDueAt(), item.getNewDueAt(), item.getComment(),
                                item.getChangedBy(), item.getChangedAt(), names.get(item.getChangedBy()),
                                names.get(item.getPreviousAssigneeId()), names.get(item.getNewAssigneeId())))
                        .toList());
    }

    private static RelatedDocumentView documentView(CommercialDocument document, String customerName) {
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
                .toList(), customerName);
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

    public record WorkUpdateRequest(
            @NotNull ControlAlertPriority priority,
            UUID assigneeId,
            Instant dueAt,
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
            ControlAlertPriority priority,
            UUID assigneeId,
            Instant dueAt,
            Instant updatedAt,
            long version,
            String terminalName,
            String reviewComment,
            String assigneeName) {
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

    public record AlertDetailView(
            AlertSummaryView alert,
            List<HistoryView> history,
            List<WorkHistoryView> workHistory) {
    }

    public record HistoryView(
            ControlAlertStatus previousStatus,
            ControlAlertStatus newStatus,
            String comment,
            UUID changedBy,
            Instant changedAt,
            String changedByName) {
    }

    public record WorkHistoryView(
            ControlAlertPriority previousPriority,
            ControlAlertPriority newPriority,
            UUID previousAssigneeId,
            UUID newAssigneeId,
            Instant previousDueAt,
            Instant newDueAt,
            String comment,
            UUID changedBy,
            Instant changedAt,
            String changedByName,
            String previousAssigneeName,
            String newAssigneeName) {
    }

    public record AssigneeOptionView(UUID id, String name, String userName) {
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
            List<RelatedDocumentPaymentView> payments,
            String customerName) {
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
