package com.tpverp.backend.sync;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SyncOutboxIncidentService {

    private final SyncOutboxEventRepository events;
    private final CurrentOrganization organization;
    private final AuditService audit;
    private final Clock clock;

    public SyncOutboxIncidentService(
            SyncOutboxEventRepository events,
            CurrentOrganization organization,
            AuditService audit,
            Clock clock) {
        this.events = events;
        this.organization = organization;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SyncOutboxIncidentView> listDeadLetters() {
        UUID companyId = organization.currentCompany().getId();
        UUID storeId = organization.currentStore().getId();
        return events.findIncidentsForStore(
                        companyId, storeId, SyncOutboxStatus.DEAD_LETTER,
                        PageRequest.of(0, 100)).stream()
                .map(SyncOutboxIncidentView::from)
                .toList();
    }

    @Transactional
    public SyncOutboxIncidentView retry(
            UUID eventId,
            long expectedVersion,
            String reason) {
        String normalizedReason = requireReason(reason);
        UUID companyId = organization.currentCompany().getId();
        UUID storeId = organization.currentStore().getId();
        SyncOutboxEvent event = events.findLockedByEventId(eventId)
                .filter(candidate -> candidate.getCompanyId().equals(companyId))
                .filter(candidate -> candidate.getStoreId() == null
                        || candidate.getStoreId().equals(storeId))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Incidencia de sincronizacion no encontrada"));
        if (event.getVersion() != expectedVersion) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "La incidencia ha cambiado; actualice el listado antes de reintentar");
        }
        if (event.getStatus() != SyncOutboxStatus.DEAD_LETTER) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "La incidencia ya no esta en DEAD_LETTER");
        }
        int previousAttempts = event.getAttempts();
        event.reopenForManualRetry(clock.instant());
        audit.record(
                "SYNC_OUTBOX_MANUAL_RETRY",
                AuditResult.EXITO,
                Map.of(
                        "eventId", event.getEventId().toString(),
                        "entityType", event.getEntityType(),
                        "entityId", event.getEntityId().toString(),
                        "previousAttempts", previousAttempts,
                        "reason", normalizedReason));
        return SyncOutboxIncidentView.from(event);
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "El motivo del reintento es obligatorio");
        }
        String normalized = reason.trim();
        if (normalized.length() > 500) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "El motivo no puede superar 500 caracteres");
        }
        return normalized;
    }
}
