package com.tpverp.backend.security.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Empresa;
import com.tpverp.backend.organization.Tienda;
import com.tpverp.backend.security.application.AuthenticationService;
import com.tpverp.backend.security.domain.Rol;
import com.tpverp.backend.security.domain.Sesion;
import com.tpverp.backend.security.domain.SesionRepository;
import com.tpverp.backend.security.domain.Usuario;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TipoTerminal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

class BearerSessionFilterTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsSessionWhenTerminalWasDeactivated() throws Exception {
        SecurityContextHolder.clearContext();
        var sessions = org.mockito.Mockito.mock(SesionRepository.class);
        var authentication = org.mockito.Mockito.mock(AuthenticationService.class);
        var terminal = new Terminal(
                store(), "TPV", TipoTerminal.TERMINAL_VENTA, "hash");
        terminal.desactivar();
        var role = new Rol(terminal.getTienda(), "CAJA");
        var user = new Usuario(terminal.getTienda(), "USER", "hash", role);
        var session = new Sesion(user, terminal, "token-hash", Instant.now());
        when(authentication.hash("token")).thenReturn("token-hash");
        when(sessions.findByTokenHashAndRevocadaEnIsNull("token-hash"))
                .thenReturn(Optional.of(session));
        var request = new MockHttpServletRequest("GET", "/api/v1/products");
        request.addHeader("Authorization", "Bearer token");

        invoke(new BearerSessionFilter(sessions, authentication), request);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static void invoke(
            OncePerRequestFilter filter, MockHttpServletRequest request) throws Exception {
        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (servletRequest, servletResponse) -> {
                });
    }

    private static Tienda store() {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
        return new Tienda(
                new Empresa("B00000000", "Empresa", address),
                "Tienda", address, UUID.randomUUID().toString(),
                "Atlantic/Canary", "EUR", "es-ES");
    }
}
