package com.tpverp.saas.fiscal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.license.SaasInstallation;
import com.tpverp.saas.sync.SaasSyncEvent;
import com.tpverp.saas.sync.SyncOperation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FiscalStatusSyncProjector {

    public static final String ENTITY_TYPE = "FISCAL_STATUS";
    private static final long MAX_FUTURE_SKEW_SECONDS = 300;

    private final SaasFiscalStatusRepository statuses;
    private final EntityManager entityManager;
    private final ObjectMapper mapper;
    private final Clock clock;

    public FiscalStatusSyncProjector(
            SaasFiscalStatusRepository statuses,
            EntityManager entityManager,
            ObjectMapper mapper,
            Clock clock) {
        this.statuses = statuses;
        this.entityManager = entityManager;
        this.mapper = mapper;
        this.clock = clock;
    }

    public boolean supports(String entityType, SyncOperation operation) {
        return ENTITY_TYPE.equals(entityType)
                && (operation == SyncOperation.CREAR || operation == SyncOperation.ACTUALIZAR);
    }

    @Transactional(noRollbackFor = ProjectionException.class)
    public void project(SaasSyncEvent event, Map<String, Object> payload, Instant receivedAt) {
        SaasInstallation installation = event.getInstallation();
        if (installation == null || !installation.getInstallationId().equals(uuid(payload, "installationId"))) {
            throw conflict("La identidad de instalacion fiscal no coincide con el token");
        }
        UUID companyId = uuid(payload, "companyId");
        UUID storeId = uuid(payload, "storeId");
        if (!event.getCompany().getId().equals(companyId)
                || event.getStore() == null || !event.getStore().getId().equals(storeId)) {
            throw conflict("La procedencia fiscal no coincide con la instalacion autenticada");
        }
        String mode = text(payload, "effectiveMode");
        if (!SetValues.MODES.contains(mode)) throw conflict("Modalidad fiscal no soportada");
        String state = text(payload, "activationState");
        if (!SetValues.STATES.contains(state)) throw conflict("Estado de activacion fiscal no soportado");
        String runtimeClass = supportedText(payload, "runtimeClass", SetValues.RUNTIME_CLASSES);
        String endpointEnvironment = supportedText(
                payload, "endpointEnvironment", SetValues.ENDPOINT_ENVIRONMENTS);
        String transportMode = supportedText(payload, "transportMode", SetValues.TRANSPORT_MODES);
        if (("SANDBOX".equals(runtimeClass)
                    && (!"TEST".equals(endpointEnvironment) || !"SIMULATED".equals(transportMode)))
                || ("REAL".equals(runtimeClass) && !"AEAT".equals(transportMode))
                || ("PRODUCTION".equals(endpointEnvironment) && !"REAL".equals(runtimeClass))) {
            throw conflict("Combinacion de entorno fiscal no soportada");
        }
        long modeVersion = nonNegativeLong(payload, "modeVersion");
        Instant reportedAt = instant(payload, "reportedAt");
        if (reportedAt.isAfter(clock.instant().plusSeconds(MAX_FUTURE_SKEW_SECONDS))) {
            throw conflict("El estado fiscal esta fechado en el futuro");
        }

        // La fila de instalacion existe antes de la primera proyeccion y serializa tambien ese primer INSERT.
        installation = entityManager.find(
                SaasInstallation.class,
                installation.getId(),
                LockModeType.PESSIMISTIC_WRITE);
        if (installation == null) {
            throw conflict("La instalacion fiscal ya no existe");
        }
        var current = statuses.findByInstallation_Id(installation.getId()).orElse(null);
        if (current != null) {
            if (modeVersion < current.getModeVersion()
                    || (modeVersion == current.getModeVersion()
                        && reportedAt.isBefore(current.getReportedAt()))) {
                return;
            }
            if (modeVersion == current.getModeVersion()) {
                if (reportedAt.equals(current.getReportedAt())) {
                    if (!current.getPayloadHash().equals(event.getPayloadHash())) {
                        throw conflict("Misma version y fecha fiscal con contenido diferente");
                    }
                    return;
                }
                if (!current.getEffectiveMode().equals(mode)
                        || !Objects.equals(current.getModeSince(), optionalInstant(payload, "modeSince"))) {
                    throw conflict("La modalidad fiscal ha cambiado sin incrementar su version");
                }
            }
            current.update(mode, state, modeVersion, optionalInstant(payload, "modeSince"),
                    optionalDate(payload, "activationDate"), optionalLong(payload, "policyVersion"),
                    runtimeClass, endpointEnvironment, transportMode,
                    reportedAt, receivedAt, event.getPayloadHash());
            return;
        }
        statuses.save(new SaasFiscalStatus(UUID.randomUUID(), installation, event.getCompany(),
                event.getStore(), installation.getInstallationId(), event.getEntityId(), mode, state,
                modeVersion, optionalInstant(payload, "modeSince"), optionalDate(payload, "activationDate"),
                optionalLong(payload, "policyVersion"), runtimeClass,
                endpointEnvironment, transportMode, reportedAt,
                receivedAt, event.getPayloadHash()));
    }

    private UUID uuid(Map<String, Object> payload, String key) {
        try { return UUID.fromString(text(payload, key)); }
        catch (RuntimeException ex) { throw conflict("Campo fiscal invalido: " + key); }
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null || value.toString().isBlank()) throw conflict("Campo fiscal obligatorio: " + key);
        return value.toString();
    }

    private static String supportedText(
            Map<String, Object> payload, String key, java.util.Set<String> supported) {
        String value = text(payload, key);
        if (!supported.contains(value)) {
            throw conflict("Campo fiscal no soportado: " + key);
        }
        return value;
    }

    private static long nonNegativeLong(Map<String, Object> payload, String key) {
        try {
            long value = Long.parseLong(text(payload, key));
            if (value < 0) throw new NumberFormatException();
            return value;
        } catch (RuntimeException ex) { throw conflict("Campo fiscal invalido: " + key); }
    }

    private static Long optionalLong(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null || value.toString().isBlank()) return null;
        try { long parsed = Long.parseLong(value.toString()); return parsed < 0 ? null : parsed; }
        catch (NumberFormatException ex) { throw conflict("Campo fiscal invalido: " + key); }
    }

    private static Instant instant(Map<String, Object> payload, String key) {
        try {
            Object raw = payload.get(key);
            if (raw instanceof Number number) {
                BigDecimal value = new BigDecimal(number.toString());
                long seconds = value.longValue();
                int nanos = value.subtract(BigDecimal.valueOf(seconds))
                        .movePointRight(9)
                        .setScale(0, RoundingMode.DOWN)
                        .intValueExact();
                return Instant.ofEpochSecond(seconds, nanos);
            }
            return Instant.parse(text(payload, key));
        }
        catch (RuntimeException ex) { throw conflict("Campo fiscal invalido: " + key); }
    }

    private static Instant optionalInstant(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null || value.toString().isBlank()) return null;
        try { return Instant.parse(value.toString()); }
        catch (RuntimeException ex) { throw conflict("Campo fiscal invalido: " + key); }
    }

    private static LocalDate optionalDate(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null || value.toString().isBlank()) return null;
        try {
            if (value instanceof List<?> parts && parts.size() == 3) {
                return LocalDate.of(
                        integer(parts.get(0)),
                        integer(parts.get(1)),
                        integer(parts.get(2)));
            }
            return LocalDate.parse(value.toString());
        }
        catch (RuntimeException ex) { throw conflict("Campo fiscal invalido: " + key); }
    }

    private static int integer(Object value) {
        return new BigDecimal(value.toString()).intValueExact();
    }

    private static ProjectionException conflict(String message) {
        return new ProjectionException(message);
    }

    public static class ProjectionException extends ResponseStatusException {
        public ProjectionException(String message) {
            super(HttpStatus.CONFLICT, message);
        }
    }

    private static final class SetValues {
        private static final java.util.Set<String> MODES = java.util.Set.of("PRE_SIF", "NO_VERIFACTU", "VERIFACTU");
        private static final java.util.Set<String> STATES = java.util.Set.of("ACTIVE", "PENDING", "DUE_REVIEW", "UNKNOWN");
        private static final java.util.Set<String> RUNTIME_CLASSES = java.util.Set.of("SANDBOX", "REAL");
        private static final java.util.Set<String> ENDPOINT_ENVIRONMENTS = java.util.Set.of("TEST", "PRODUCTION");
        private static final java.util.Set<String> TRANSPORT_MODES = java.util.Set.of("SIMULATED", "AEAT");
    }
}
