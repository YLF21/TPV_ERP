package com.tpverp.backend.cash;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.ABRIR_CAJON;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.control.ControlAlertDetectionService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.application.OperationalPermissionAuthorizationService;
import com.tpverp.backend.terminal.TerminalRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CashDrawerService {

    private static final Duration AUTHORIZATION_VALIDITY = Duration.ofMinutes(2);

    private final TerminalRepository terminals;
    private final CurrentOrganization organization;
    private final OperationalPermissionAuthorizationService authorizations;
    private final ControlAlertDetectionService controlAlerts;
    private final AuditService audit;
    private final Clock clock;
    private final Map<UUID, OpenAuthorization> pending = new ConcurrentHashMap<>();

    public CashDrawerService(
            TerminalRepository terminals,
            CurrentOrganization organization,
            OperationalPermissionAuthorizationService authorizations,
            ControlAlertDetectionService controlAlerts,
            AuditService audit,
            Clock clock) {
        this.terminals = terminals;
        this.organization = organization;
        this.authorizations = authorizations;
        this.controlAlerts = controlAlerts;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public AuthorizationView authorize(
            UUID terminalId,
            String authorizerUsername,
            String authorizerPassword,
            Authentication authentication) {
        var store = organization.currentStore();
        var terminal = terminals.findByIdAndTiendaId(terminalId, store.getId())
                .filter(value -> value.isActiva() && value.isAprobada())
                .orElseThrow(() -> new IllegalArgumentException("Terminal no activa o no aprobada"));
        var authorization = authorizations.authorize(
                ABRIR_CAJON, authorizerUsername, authorizerPassword, authentication);
        var operationId = UUID.randomUUID();
        var now = Instant.now(clock);
        var expiresAt = now.plus(AUTHORIZATION_VALIDITY);
        var grant = new OpenAuthorization(
                operationId,
                store.getId(),
                terminal.getId(),
                terminal.getNombre(),
                authorization.operator().getId(),
                authorization.operator().getUserName(),
                authorization.authorizer().getId(),
                authorization.authorizer().getUserName(),
                authorization.delegated(),
                expiresAt);
        pending.put(operationId, grant);
        purgeExpired(now);
        audit.record("CASH_DRAWER_OPEN_AUTHORIZED", AuditResult.EXITO, auditDetails(grant));
        return new AuthorizationView(
                operationId,
                authorization.authorizer().getUserName(),
                authorization.delegated(),
                expiresAt);
    }

    @Transactional
    public synchronized CompletionView complete(
            UUID operationId,
            boolean opened,
            String errorCode,
            String errorMessage,
            Authentication authentication) {
        var grant = pending.get(operationId);
        var now = Instant.now(clock);
        if (grant == null || grant.expiresAt().isBefore(now)) {
            pending.remove(operationId);
            throw new IllegalStateException("La autorizacion de apertura ha caducado");
        }
        var operator = organization.currentUser(authentication);
        if (!grant.operatorId().equals(operator.getId())
                || !grant.storeId().equals(organization.currentStore().getId())) {
            throw new IllegalStateException("La autorizacion no pertenece a esta sesion");
        }
        var details = new LinkedHashMap<>(auditDetails(grant));
        if (errorCode != null && !errorCode.isBlank()) details.put("errorCode", limit(errorCode, 64));
        if (errorMessage != null && !errorMessage.isBlank()) details.put("errorMessage", limit(errorMessage, 300));
        if (opened) {
            controlAlerts.detectCashDrawerOpened(
                    grant.operationId(),
                    grant.terminalId(),
                    grant.terminalName(),
                    grant.authorizerId(),
                    grant.authorizerName(),
                    grant.delegated(),
                    authentication);
            audit.record("CASH_DRAWER_OPENED", AuditResult.EXITO, details);
        } else {
            audit.record("CASH_DRAWER_OPEN_FAILED", AuditResult.FALLO, details);
        }
        pending.remove(operationId);
        return new CompletionView(operationId, opened);
    }

    private Map<String, Object> auditDetails(OpenAuthorization grant) {
        var details = new LinkedHashMap<String, Object>();
        details.put("operationId", grant.operationId().toString());
        details.put("terminalId", grant.terminalId().toString());
        details.put("terminalCode", grant.terminalName());
        details.put("operatorId", grant.operatorId().toString());
        details.put("operatorName", grant.operatorName());
        details.put("authorizerId", grant.authorizerId().toString());
        details.put("authorizerName", grant.authorizerName());
        details.put("delegated", grant.delegated());
        return details;
    }

    private void purgeExpired(Instant now) {
        pending.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private static String limit(String value, int maxLength) {
        var normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private record OpenAuthorization(
            UUID operationId,
            UUID storeId,
            UUID terminalId,
            String terminalName,
            UUID operatorId,
            String operatorName,
            UUID authorizerId,
            String authorizerName,
            boolean delegated,
            Instant expiresAt) {
    }

    public record AuthorizationView(
            UUID operationId,
            String authorizedBy,
            boolean delegated,
            Instant expiresAt) {
    }

    public record CompletionView(UUID operationId, boolean opened) {
    }
}
