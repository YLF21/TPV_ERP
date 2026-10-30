package com.tpverp.saas.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.tpverp.saas.admin.AdminAuditService;
import com.tpverp.saas.license.SaasInstallation;
import com.tpverp.saas.plan.PlanLimitService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminSyncRecoveryService {
    private final EntityManager entityManager;
    private final SaasSyncEventRepository events;
    private final PlanLimitService plans;
    private final SyncEventService projections;
    private final AdminAuditService audit;
    private final ObjectReader payloadReader;
    private final Clock clock;

    public AdminSyncRecoveryService(EntityManager entityManager, SaasSyncEventRepository events,
            PlanLimitService plans, SyncEventService projections, AdminAuditService audit,
            ObjectMapper mapper, Clock clock) {
        this.entityManager = entityManager;
        this.events = events;
        this.plans = plans;
        this.projections = projections;
        this.audit = audit;
        this.payloadReader = mapper.readerFor(new TypeReference<Map<String, Object>>() {})
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.clock = clock;
    }

    @Transactional(rollbackFor = Exception.class)
    public Result recover(UUID eventId, Request request) {
        if (eventId == null || request == null || request.companyId() == null
                || request.expectedPayloadHash() == null
                || !request.expectedPayloadHash().matches("[0-9a-f]{64}")
                || request.reason() == null || request.reason().isBlank() || request.reason().length() > 1000
                || request.expectedStatus() != SaasSyncEvent.ProjectionStatus.RECEIVED
                    && request.expectedStatus() != SaasSyncEvent.ProjectionStatus.ERROR) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Solicitud de recuperacion invalida");
        }
        // Keep the ingestion lock order: company, installation, event.
        plans.lockCompany(request.companyId());
        SaasSyncEvent event = events.findById(eventId)
                .filter(value -> value.getCompany().getId().equals(request.companyId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Evento no encontrado"));
        SaasInstallation installation = event.getInstallation();
        if (installation == null) throw conflict("El evento no conserva su instalacion de origen");
        entityManager.refresh(installation, LockModeType.PESSIMISTIC_READ);
        events.ensureEventLock(eventId);
        events.lockEvent(eventId);
        entityManager.refresh(event);

        if (!installation.isActive() || event.getStore() == null
                || !event.getCompany().getId().equals(request.companyId())
                || !installation.getCompany().getId().equals(event.getCompany().getId())
                || !event.getStore().getCompany().getId().equals(event.getCompany().getId())
                || installation.getStore() == null
                || !installation.getStore().getId().equals(event.getStore().getId())
                || !Objects.equals(event.getInstallation().getId(), installation.getId())) {
            throw conflict("La procedencia del evento no es compatible con la instalacion");
        }
        if (!request.expectedPayloadHash().equals(event.getPayloadHash())
                || !event.getPayloadHash().equals(hash(event.getPayload()))) {
            throw conflict("La huella del evento no coincide con el contenido original");
        }
        var before = event.getProjectionStatus();
        if (before == SaasSyncEvent.ProjectionStatus.PROJECTED
                || before == SaasSyncEvent.ProjectionStatus.IGNORED) {
            if (event.getProjectionError() != null) throw conflict("El evento conserva un conflicto que requiere revision");
            return new Result(eventId, before, before, event.getProjectedAt(), true);
        }
        if (before != request.expectedStatus()) throw conflict("El estado del evento ha cambiado");

        Map<String, Object> payload;
        try {
            payload = payloadReader.readValue(event.getPayload());
        } catch (Exception exception) {
            throw conflict("El contenido original no es un objeto JSON valido");
        }
        var persistedRequest = new SyncEventRequest(eventId, event.getCompany().getId(),
                event.getStore().getId(), event.getStoreSequence(), null, event.getEntityType(),
                event.getEntityId(), event.getOperation(), payload);
        Instant now = clock.instant();
        // Do not call receive(): no new receipt, hash, payload, sequence or capacity charge.
        projections.projectPersisted(event, persistedRequest, now);
        events.flush();
        audit.log("RECOVER_SYNC_EVENT", "SYNC_EVENT", eventId.toString(),
                "companyId=" + request.companyId() + "; payloadHash=" + event.getPayloadHash()
                        + "; from=" + before + "; to=" + event.getProjectionStatus()
                        + "; reason=" + request.reason().trim());
        return new Result(eventId, before, event.getProjectionStatus(), event.getProjectedAt(), false);
    }

    private static String hash(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record Request(@NotNull UUID companyId,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String expectedPayloadHash,
            @NotNull SaasSyncEvent.ProjectionStatus expectedStatus,
            @NotBlank @Size(max = 1000) String reason) {}

    public record Result(UUID eventId, SaasSyncEvent.ProjectionStatus previousStatus,
            SaasSyncEvent.ProjectionStatus status, Instant projectedAt, boolean replayed) {}
}
