package com.tpverp.backend.terminal;

import com.tpverp.backend.security.application.AuthenticationService;
import com.tpverp.backend.security.domain.SesionRepository;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class CurrentTerminal {

    private final SesionRepository sessions;
    private final AuthenticationService authenticationService;

    public CurrentTerminal(
            SesionRepository sessions,
            AuthenticationService authenticationService) {
        this.sessions = sessions;
        this.authenticationService = authenticationService;
    }

    // Resuelve la terminal desde el token autenticado, nunca desde entrada de usuario.
    public UUID terminalId(Authentication authentication) {
        if (authentication == null || !(authentication.getCredentials() instanceof String token)
                || token.isBlank()) {
            throw new IllegalStateException("No hay una sesion autenticada con terminal");
        }
        return sessions.findByTokenHashAndRevocadaEnIsNull(authenticationService.hash(token))
                .map(session -> session.getTerminal())
                .filter(terminal -> terminal.isActiva() && terminal.isAprobada())
                .map(Terminal::getId)
                .orElseThrow(() -> new IllegalStateException(
                        "No hay una terminal resoluble para la sesion autenticada"));
    }
}
