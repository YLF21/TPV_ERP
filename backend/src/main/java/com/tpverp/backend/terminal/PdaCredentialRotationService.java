package com.tpverp.backend.terminal;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.application.AuthenticationService;
import com.tpverp.backend.security.domain.UserSession;
import com.tpverp.backend.security.domain.UserSessionRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rotates only the permanent credential of the PDA bound to the calling session. */
@Service
public class PdaCredentialRotationService {
    private final TerminalRepository terminals;
    private final UserSessionRepository sessions;
    private final AuthenticationService authenticationService;
    private final CurrentOrganization organization;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PdaCredentialRotationService(
            TerminalRepository terminals,
            UserSessionRepository sessions,
            AuthenticationService authenticationService,
            CurrentOrganization organization,
            PasswordEncoder passwordEncoder,
            AuditService auditService,
            Clock clock) {
        this.terminals = terminals;
        this.sessions = sessions;
        this.authenticationService = authenticationService;
        this.organization = organization;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public RotationResult rotate(Authentication authentication) {
        var currentSession = currentSession(authentication);
        var sessionTerminal = currentSession.getTerminal();
        if (sessionTerminal == null) {
            throw new IllegalStateException("PDA_CREDENTIAL_ROTATION_REQUIRES_TERMINAL");
        }
        var store = organization.currentStore();
        var terminal = terminals.findByIdAndTiendaIdForUpdate(sessionTerminal.getId(), store.getId())
                .orElseThrow(() -> new IllegalStateException("PDA_CREDENTIAL_ROTATION_TERMINAL_NOT_FOUND"));
        if (terminal.getTipo() != TerminalType.PDA || !terminal.isActiva() || !terminal.isAprobada()
                || !currentSession.hasCurrentTerminalBinding()) {
            throw new IllegalStateException("PDA_CREDENTIAL_ROTATION_NOT_ALLOWED");
        }

        var credential = newCredential();
        var now = Instant.now(clock);
        terminal.rotateCredential(passwordEncoder.encode(credential));

        // A login racing with this operation must acquire the same terminal row lock.
        // Any session created before the rotation is therefore visible and revoked here.
        sessions.findByTerminalIdAndRevocadaEnIsNull(terminal.getId()).stream()
                .filter(session -> !session.getId().equals(currentSession.getId()))
                .forEach(session -> session.revocar(
                        currentSession.getUsuario(), "PDA_CREDENTIAL_ROTATED", now));

        auditService.recordForStore(
                terminal.getTienda(),
                "PDA_CREDENTIAL_ROTATED",
                AuditResult.EXITO,
                Map.of("terminalId", terminal.getId(), "sessionId", currentSession.getId()));
        return new RotationResult(terminal.getId(), credential, now);
    }

    private UserSession currentSession(Authentication authentication) {
        if (authentication == null || !(authentication.getCredentials() instanceof String token)
                || token.isBlank()) {
            throw new BadCredentialsException("PDA_CREDENTIAL_ROTATION_REQUIRES_SESSION");
        }
        return sessions.findByTokenHashAndRevocadaEnIsNull(authenticationService.hash(token))
                .filter(UserSession::isActiva)
                .orElseThrow(() -> new BadCredentialsException(
                        "PDA_CREDENTIAL_ROTATION_REQUIRES_SESSION"));
    }

    private String newCredential() {
        var bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record RotationResult(UUID terminalId, String terminalCredential, Instant rotatedAt) {
    }
}
