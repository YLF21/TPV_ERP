package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.installation.InstallationStatusService;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.security.domain.UserSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class TerminalPairingServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-29T10:00:00Z");

    @Test
    void createsATenMinuteSingleDevicePairingCode() {
        var fixture = fixture();
        when(fixture.terminals.findByIdAndTiendaId(fixture.terminal.getId(), fixture.store.getId()))
                .thenReturn(Optional.of(fixture.terminal));
        when(fixture.pairings.findActiveForUpdate(fixture.terminal.getId())).thenReturn(List.of());
        when(fixture.pairings.save(any(PdaPairingGrant.class))).thenAnswer(call -> call.getArgument(0));

        var result = fixture.service.createPdaPairingCode(fixture.terminal.getId());

        assertThat(result.code()).matches("[A-Z2-9]{4}-[A-Z2-9]{4}");
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(600));
        verify(fixture.pairings).save(any(PdaPairingGrant.class));
    }

    @Test
    void linkingRotatesTheCredentialAndReturnsAnApprovedIdentity() {
        var fixture = fixture();
        var grant = mock(PdaPairingGrant.class);
        when(fixture.pairings.findForUpdateByCodeHash(anyString())).thenReturn(Optional.of(grant));
        when(grant.consume(NOW)).thenReturn(fixture.terminal);
        when(fixture.encoder.encode(anyString())).thenReturn("rotated-hash");
        when(fixture.sessions.findByTerminalIdAndRevocadaEnIsNull(fixture.terminal.getId()))
                .thenReturn(List.of());

        var result = fixture.service.linkPda("ABCD-EFGH");

        assertThat(result.terminalId()).isEqualTo(fixture.terminal.getId());
        assertThat(result.terminalCode()).isEqualTo("PDA 1");
        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.terminalCredential()).isNotBlank();
        assertThat(fixture.terminal.getCredentialHash()).isEqualTo("rotated-hash");
    }

    private static Fixture fixture() {
        var store = store();
        var terminal = new Terminal(store, "PDA 1", TerminalType.PDA, "old-hash");
        var terminals = mock(TerminalRepository.class);
        var pairings = mock(PdaPairingGrantRepository.class);
        var organization = mock(CurrentOrganization.class);
        var encoder = mock(PasswordEncoder.class);
        var sessions = mock(UserSessionRepository.class);
        when(organization.currentStore()).thenReturn(store);
        var service = new TerminalRegistrationService(
                terminals, pairings, mock(StoreRepository.class), organization,
                mock(LicenseRepository.class), mock(InstallationStatusService.class),
                encoder, sessions, Clock.fixed(NOW, ZoneOffset.UTC), mock(AuditService.class));
        return new Fixture(service, terminals, pairings, encoder, sessions, store, terminal);
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
            TerminalRegistrationService service,
            TerminalRepository terminals,
            PdaPairingGrantRepository pairings,
            PasswordEncoder encoder,
            UserSessionRepository sessions,
            Store store,
            Terminal terminal) {
    }
}
