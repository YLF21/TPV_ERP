package com.tpverp.backend.security.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.application.AuthenticationService;
import com.tpverp.backend.security.domain.OperationalSessionContext;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserSession;
import com.tpverp.backend.security.domain.UserSessionRepository;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalType;
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
    void exposesTerminalStoreAsOperationalSessionContext() throws Exception {
        var sessions = org.mockito.Mockito.mock(UserSessionRepository.class);
        var authentication = org.mockito.Mockito.mock(AuthenticationService.class);
        var terminal = new Terminal(store(), "SERVER", TerminalType.SERVIDOR, "hash");
        var role = new Role(null, "ADMIN");
        var user = new UserAccount(null, "ADMIN", "hash", role);
        var session = new UserSession(user, terminal, "token-hash", Instant.now());
        when(authentication.hash("token")).thenReturn("token-hash");
        when(sessions.findByTokenHashAndRevocadaEnIsNull("token-hash"))
                .thenReturn(Optional.of(session));
        var request = new MockHttpServletRequest("GET", "/api/v1/products");
        request.addHeader("Authorization", "Bearer token");

        invoke(new BearerSessionFilter(sessions, authentication), request);

        var authenticated = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authenticated).isNotNull();
        assertThat(authenticated.getPrincipal()).isSameAs(user);
        assertThat(authenticated.getDetails()).isEqualTo(new OperationalSessionContext(
                terminal.getId(), terminal.getTienda().getId()));
    }

    @Test
    void installationSessionDoesNotInventOperationalStoreContext() throws Exception {
        var sessions = org.mockito.Mockito.mock(UserSessionRepository.class);
        var authentication = org.mockito.Mockito.mock(AuthenticationService.class);
        var role = new Role(null, "ADMIN");
        var user = new UserAccount(null, "ADMIN", "hash", role);
        var session = new UserSession(user, null, "token-hash", Instant.now());
        when(authentication.hash("token")).thenReturn("token-hash");
        when(sessions.findByTokenHashAndRevocadaEnIsNull("token-hash"))
                .thenReturn(Optional.of(session));
        var request = new MockHttpServletRequest("GET", "/api/v1/installation/status");
        request.addHeader("Authorization", "Bearer token");

        invoke(new BearerSessionFilter(sessions, authentication), request);

        var authenticated = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authenticated).isNotNull();
        assertThat(authenticated.getDetails()).isNull();
    }

    @Test
    void rejectsSessionWhenTerminalWasDeactivated() throws Exception {
        SecurityContextHolder.clearContext();
        var sessions = org.mockito.Mockito.mock(UserSessionRepository.class);
        var authentication = org.mockito.Mockito.mock(AuthenticationService.class);
        var terminal = new Terminal(
                store(), "TPV", TerminalType.TERMINAL_VENTA, "hash");
        terminal.deactivate();
        var role = new Role(terminal.getTienda(), "CAJA");
        var user = new UserAccount(terminal.getTienda(), "USER", "hash", role);
        var session = new UserSession(user, terminal, "token-hash", Instant.now());
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

    private static Store store() {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
        return new Store(
                new Company("B00000000", "Company", address),
                "Store", address, UUID.randomUUID().toString(),
                "Atlantic/Canary", "EUR", "es-ES");
    }
}
