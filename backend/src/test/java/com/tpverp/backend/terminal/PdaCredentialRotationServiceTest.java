package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.application.AuthenticationService;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserSession;
import com.tpverp.backend.security.domain.UserSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

class PdaCredentialRotationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-29T18:00:00Z");

    @Test
    void rotatesOnlyCurrentPdaAndRevokesEveryOtherSession() {
        var fixture = fixture(TerminalType.PDA);
        var other = new UserSession(fixture.user, fixture.terminal, "other-token-hash", NOW.minusSeconds(60));
        when(fixture.sessions.findByTerminalIdAndRevocadaEnIsNull(fixture.terminal.getId()))
                .thenReturn(List.of(fixture.currentSession, other));
        when(fixture.encoder.encode(anyString())).thenReturn("rotated-hash");

        var result = fixture.service.rotate(fixture.authentication);

        assertThat(result.terminalId()).isEqualTo(fixture.terminal.getId());
        assertThat(result.terminalCredential()).hasSize(43);
        assertThat(result.rotatedAt()).isEqualTo(NOW);
        assertThat(fixture.terminal.getCredentialHash()).isEqualTo("rotated-hash");
        assertThat(fixture.currentSession.isActiva()).isTrue();
        assertThat(other.isActiva()).isFalse();
        verify(fixture.audit).recordForStore(
                fixture.store,
                "PDA_CREDENTIAL_ROTATED",
                AuditResult.EXITO,
                Map.of("terminalId", fixture.terminal.getId(),
                        "sessionId", fixture.currentSession.getId()));
    }

    @Test
    void rejectsAuthenticatedSessionFromNonPdaTerminalWithoutChangingCredential() {
        var fixture = fixture(TerminalType.TERMINAL_VENTA);

        assertThatThrownBy(() -> fixture.service.rotate(fixture.authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PDA_CREDENTIAL_ROTATION_NOT_ALLOWED");

        assertThat(fixture.terminal.getCredentialHash()).isEqualTo("old-hash");
        verify(fixture.encoder, never()).encode(anyString());
    }

    @Test
    void rejectsMissingOrUnknownBearerSession() {
        var fixture = fixture(TerminalType.PDA);
        when(fixture.sessions.findByTokenHashAndRevocadaEnIsNull("token-hash"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> fixture.service.rotate(fixture.authentication))
                .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class)
                .hasMessage("PDA_CREDENTIAL_ROTATION_REQUIRES_SESSION");
        verify(fixture.terminals, never()).findByIdAndTiendaIdForUpdate(
                fixture.terminal.getId(), fixture.store.getId());
    }

    private static Fixture fixture(TerminalType type) {
        var store = store();
        var terminal = new Terminal(store, "DEVICE", type, "old-hash");
        var role = new Role(store, "PDA");
        var user = new UserAccount(store, "OPERATOR", "user-hash", role);
        var currentSession = new UserSession(user, terminal, "token-hash", NOW.minusSeconds(60));
        var terminals = mock(TerminalRepository.class);
        var sessions = mock(UserSessionRepository.class);
        var authenticationService = mock(AuthenticationService.class);
        var organization = mock(CurrentOrganization.class);
        var encoder = mock(PasswordEncoder.class);
        var audit = mock(AuditService.class);
        var authentication = new UsernamePasswordAuthenticationToken(user, "raw-token");
        when(authenticationService.hash("raw-token")).thenReturn("token-hash");
        when(sessions.findByTokenHashAndRevocadaEnIsNull("token-hash"))
                .thenReturn(Optional.of(currentSession));
        when(organization.currentStore()).thenReturn(store);
        when(terminals.findByIdAndTiendaIdForUpdate(terminal.getId(), store.getId()))
                .thenReturn(Optional.of(terminal));
        var service = new PdaCredentialRotationService(
                terminals, sessions, authenticationService, organization, encoder, audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(service, terminals, sessions, encoder, audit, store, terminal,
                user, currentSession, authentication);
    }

    private static Store store() {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
        return new Store(
                new Company("B00000000", "Company", address),
                "001", "Store", address, UUID.randomUUID().toString(),
                "Atlantic/Canary", "EUR", "es-ES");
    }

    private record Fixture(
            PdaCredentialRotationService service,
            TerminalRepository terminals,
            UserSessionRepository sessions,
            PasswordEncoder encoder,
            AuditService audit,
            Store store,
            Terminal terminal,
            UserAccount user,
            UserSession currentSession,
            UsernamePasswordAuthenticationToken authentication) {
    }
}
