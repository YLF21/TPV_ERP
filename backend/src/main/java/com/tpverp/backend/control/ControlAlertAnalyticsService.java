package com.tpverp.backend.control;

import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ControlAlertAnalyticsService {

    private final ControlAlertRepository alerts;
    private final CurrentOrganization organization;
    private final Clock clock;

    public ControlAlertAnalyticsService(
            ControlAlertRepository alerts,
            CurrentOrganization organization,
            Clock clock) {
        this.alerts = alerts;
        this.organization = organization;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AnalyticsView analytics(Instant from, Instant to, int overdueHours) {
        requireRange(from, to);
        requireHours(overdueHours);
        var storeId = organization.currentStore().getId();
        var byStatus = alerts.countByStatusInRange(storeId, from, to).stream()
                .map(item -> new KeyMetric(item.getStatus().name(), item.getTotal()))
                .toList();
        var total = byStatus.stream().mapToLong(KeyMetric::count).sum();
        var byType = alerts.countByTypeInRange(storeId, from, to).stream()
                .map(item -> new KeyMetric(item.getType().name(), item.getTotal()))
                .toList();
        var byUser = alerts.countByUserInRange(storeId, from, to).stream()
                .map(item -> new LabeledMetric(
                        item.getUserId().toString(), item.getUserName(), item.getTotal()))
                .toList();
        var byTerminal = alerts.countByTerminalInRange(storeId, from, to).stream()
                .map(item -> item.getTerminalId() == null
                        ? new LabeledMetric("UNASSIGNED", "Sin terminal", item.getTotal())
                        : new LabeledMetric(
                                item.getTerminalId().toString(),
                                item.getTerminalId().toString(),
                                item.getTotal()))
                .toList();
        var overdue = escalationCandidatesForStore(storeId, overdueHours, 10);
        return new AnalyticsView(
                total, overdue.count(), from, to, byStatus, byType, byUser, byTerminal,
                overdue.oldest());
    }

    @Transactional(readOnly = true)
    public EscalationCandidatesView escalationCandidates(int hours, int limit) {
        requireHours(hours);
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit debe estar entre 1 y 100");
        }
        var storeId = organization.currentStore().getId();
        return escalationCandidatesForStore(storeId, hours, limit);
    }

    private EscalationCandidatesView escalationCandidatesForStore(
            UUID storeId, int hours, int limit) {
        var threshold = clock.instant().minus(hours, ChronoUnit.HOURS);
        var total = alerts.countByStoreIdAndStatusAndCreatedAtLessThanEqual(
                storeId, ControlAlertStatus.NEW, threshold);
        var pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.ASC, "createdAt", "id"));
        var oldest = alerts.findAllByStoreIdAndStatusAndCreatedAtLessThanEqual(
                        storeId, ControlAlertStatus.NEW, threshold, pageable).stream()
                .map(ControlAlertAnalyticsService::candidate)
                .toList();
        return new EscalationCandidatesView(hours, threshold, total, oldest);
    }

    private static void requireHours(int hours) {
        if (hours < 1 || hours > 720) {
            throw new IllegalArgumentException("overdueHours debe estar entre 1 y 720");
        }
    }

    private static void requireRange(Instant from, Instant to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from y to son obligatorios para consultar analitica");
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from debe ser anterior a to");
        }
    }

    private static EscalationCandidate candidate(ControlAlert alert) {
        var event = alert.getEvent();
        return new EscalationCandidate(
                alert.getId(), event.getType(), event.getRuleName(), event.getDocumentId(),
                event.getDocumentNumber(), event.getTerminalId(), event.getUserId(),
                event.getUserName(), event.getOccurredAt());
    }

    public record AnalyticsView(
            long total,
            long overdueCount,
            Instant from,
            Instant to,
            List<KeyMetric> byStatus,
            List<KeyMetric> byType,
            List<LabeledMetric> byUser,
            List<LabeledMetric> byTerminal,
            List<EscalationCandidate> overdueItems) {
    }

    public record KeyMetric(String key, long count) {
    }

    public record LabeledMetric(String key, String label, long count) {
    }

    public record EscalationCandidatesView(
            int hours,
            Instant threshold,
            long count,
            List<EscalationCandidate> oldest) {
    }

    public record EscalationCandidate(
            UUID id,
            ControlAlertType type,
            String ruleName,
            UUID documentId,
            String documentNumber,
            UUID terminalId,
            UUID userId,
            String userName,
            Instant occurredAt) {
    }
}
