package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Empresa;
import com.tpverp.backend.organization.Tienda;
import com.tpverp.backend.security.application.AuthenticationService;
import com.tpverp.backend.security.domain.Rol;
import com.tpverp.backend.security.domain.Sesion;
import com.tpverp.backend.security.domain.SesionRepository;
import com.tpverp.backend.security.domain.Usuario;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class CurrentTerminalTest {

    @Mock
    private SesionRepository sessions;
    @Mock
    private AuthenticationService authenticationService;

    @Test
    void missingCredentialsFailClearly() {
        var current = new CurrentTerminal(sessions, authenticationService);

        assertThatThrownBy(() -> current.terminalId(
                new UsernamePasswordAuthenticationToken("ADMIN", " ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sesion autenticada con terminal");
    }

    @Test
    void unresolvedTokenSessionFailsClearly() {
        var current = new CurrentTerminal(sessions, authenticationService);
        when(authenticationService.hash("token")).thenReturn("hashed-token");
        when(sessions.findByTokenHashAndRevocadaEnIsNull("hashed-token"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> current.terminalId(authentication("token")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal resoluble");
    }

    @Test
    void inactiveTerminalFailsClearly() {
        var current = new CurrentTerminal(sessions, authenticationService);
        var fixture = fixture();
        fixture.terminal.desactivar();
        when(authenticationService.hash("token")).thenReturn("hashed-token");
        when(sessions.findByTokenHashAndRevocadaEnIsNull("hashed-token"))
                .thenReturn(Optional.of(session(fixture)));

        assertThatThrownBy(() -> current.terminalId(authentication("token")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal resoluble");
    }

    @Test
    void unapprovedTerminalFailsClearly() {
        var current = new CurrentTerminal(sessions, authenticationService);
        var fixture = fixture(Terminal.solicitar(
                store(), "Caja pendiente", TipoTerminal.TERMINAL_VENTA, "terminal-hash"));
        when(authenticationService.hash("token")).thenReturn("hashed-token");
        when(sessions.findByTokenHashAndRevocadaEnIsNull("hashed-token"))
                .thenReturn(Optional.of(session(fixture)));

        assertThatThrownBy(() -> current.terminalId(authentication("token")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal resoluble");
    }

    @Test
    void activeApprovedTerminalReturnsTerminalId() {
        var current = new CurrentTerminal(sessions, authenticationService);
        var fixture = fixture();
        when(authenticationService.hash("token")).thenReturn("hashed-token");
        when(sessions.findByTokenHashAndRevocadaEnIsNull("hashed-token"))
                .thenReturn(Optional.of(session(fixture)));

        assertThat(current.terminalId(authentication("token")))
                .isEqualTo(fixture.terminal.getId());
    }

    private static UsernamePasswordAuthenticationToken authentication(String token) {
        return new UsernamePasswordAuthenticationToken("ADMIN", token);
    }

    private static Sesion session(Fixture fixture) {
        return new Sesion(fixture.user, fixture.terminal, "hashed-token", Instant.parse("2026-06-27T10:00:00Z"));
    }

    private static Fixture fixture() {
        return fixture(new Terminal(store(), "Caja 1", TipoTerminal.TERMINAL_VENTA, "terminal-hash"));
    }

    private static Fixture fixture(Terminal terminal) {
        var store = terminal.getTienda();
        var user = new Usuario(store, "ADMIN", "user-hash", new Rol(store, "ADMIN"));
        return new Fixture(terminal, user);
    }

    private static Tienda store() {
        var address = Map.of(
                "linea1", "Calle 1",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
        return new Tienda(
                new Empresa("B00000000", "Empresa", address),
                "001", "Tienda", address, "address-hash",
                "Atlantic/Canary", "EUR", "es-ES");
    }

    private record Fixture(Terminal terminal, Usuario user) {
    }
}
