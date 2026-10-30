package com.tpverp.backend.pdawork;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.OperationalSessionContext;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalRepository;
import com.tpverp.backend.terminal.TerminalType;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

class PdaOperationalObservabilityServiceTest {
    private static final UUID STORE = UUID.randomUUID();
    private static final UUID TERMINAL = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

    @Test
    void ingestionDerivesScopeFromOperationalSessionAndIsIdempotent() {
        var fixture = fixture(STORE, TERMINAL);
        UUID clientId = UUID.randomUUID();
        var command = new PdaOperationalObservabilityService.RecordCommand(clientId,
                PdaOperationalEventType.SYNC_FAILURE, " network.timeout ", null, null, NOW.minusSeconds(10), null);
        when(fixture.events.insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        var accepted = fixture.service.record(fixture.authentication, command, "request-12345678");

        assertThat(accepted.duplicate()).isFalse();
        verify(fixture.events).insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void repeatedClientEventDoesNotCreateAnotherRow() {
        var fixture = fixture(STORE, TERMINAL);
        UUID clientId = UUID.randomUUID();
        var existing = new PdaOperationalEvent(STORE, TERMINAL, clientId, PdaOperationalEventType.RECOVERED,
                "SYNC_OK", null, null, NOW.minusSeconds(20), NOW.minusSeconds(15), null);
        when(fixture.events.findByStoreIdAndTerminalIdAndClientEventId(STORE, TERMINAL, clientId))
                .thenReturn(Optional.of(existing));
        when(fixture.events.insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);

        var result = fixture.service.record(fixture.authentication,
                new PdaOperationalObservabilityService.RecordCommand(clientId, PdaOperationalEventType.RECOVERED,
                        "SYNC_OK", null, null, NOW.minusSeconds(20), null), "request-12345678");

        assertThat(result.duplicate()).isTrue();
        verify(fixture.events, never()).save(any());
    }

    @Test
    void reusedClientEventWithDifferentPayloadIsRejected() {
        var fixture = fixture(STORE, TERMINAL);
        UUID clientId = UUID.randomUUID();
        var existing = new PdaOperationalEvent(STORE, TERMINAL, clientId,
                PdaOperationalEventType.SYNC_FAILURE, "NETWORK", null, null,
                NOW.minusSeconds(20), NOW.minusSeconds(15), null);
        when(fixture.events.findByStoreIdAndTerminalIdAndClientEventId(STORE, TERMINAL, clientId))
                .thenReturn(Optional.of(existing));
        when(fixture.events.insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> fixture.service.record(fixture.authentication,
                new PdaOperationalObservabilityService.RecordCommand(clientId,
                        PdaOperationalEventType.TASK_BLOCKED, "OTHER", null, null,
                        NOW.minusSeconds(20), null), "request-12345678"))
                .isInstanceOf(PdaWorkConflictException.class)
                .hasMessage("PDA_EVENT_IDEMPOTENCY_CONFLICT");
    }

    @Test
    void rejectsNonOperationalAndCrossStoreSessions() {
        var fixture = fixture(STORE, TERMINAL);
        var command = new PdaOperationalObservabilityService.RecordCommand(UUID.randomUUID(),
                PdaOperationalEventType.SYNC_FAILURE, "NETWORK", null, null, NOW, null);
        when(fixture.authentication.getDetails()).thenReturn(null);
        assertThatThrownBy(() -> fixture.service.record(fixture.authentication, command, null))
                .isInstanceOf(AccessDeniedException.class);
        when(fixture.authentication.getDetails())
                .thenReturn(new OperationalSessionContext(UUID.randomUUID(), TERMINAL, UUID.randomUUID()));
        assertThatThrownBy(() -> fixture.service.record(fixture.authentication, command, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void rejectsEventsOutsideTheBoundedReplayWindow() {
        var fixture = fixture(STORE, TERMINAL);
        var old = new PdaOperationalObservabilityService.RecordCommand(UUID.randomUUID(),
                PdaOperationalEventType.SYNC_FAILURE, "NETWORK", null, null,
                NOW.minusSeconds(8L * 24 * 3600), null);
        var future = new PdaOperationalObservabilityService.RecordCommand(UUID.randomUUID(),
                PdaOperationalEventType.SYNC_FAILURE, "NETWORK", null, null,
                NOW.plusSeconds(301), null);
        assertThatThrownBy(() -> fixture.service.record(fixture.authentication, old, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("PDA_EVENT_TIMESTAMP_OUT_OF_RANGE");
        assertThatThrownBy(() -> fixture.service.record(fixture.authentication, future, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("PDA_EVENT_TIMESTAMP_OUT_OF_RANGE");
        verify(fixture.events, never()).insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void terminalDashboardFilterCannotEscapeTheCurrentStore() {
        var fixture = fixture(STORE, TERMINAL);
        UUID foreignTerminal = UUID.randomUUID();
        when(fixture.terminals.findByIdAndTiendaId(foreignTerminal, STORE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> fixture.service.dashboard(24, 30, 0, 50, foreignTerminal))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("PDA_TERMINAL_NOT_FOUND");
        verify(fixture.events, never()).findByStoreIdAndTerminalIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDescIdDesc(
                any(), any(), any(), any());
    }

    @Test
    void dashboardUsesStoreScopeAndFlagsInactiveApprovedPdas() {
        var fixture = fixture(STORE, TERMINAL);
        when(fixture.events.findByStoreIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDescIdDesc(
                any(), any(), any())).thenReturn(new PageImpl<>(List.of()));
        when(fixture.events.findByStoreIdAndTipoInAndOccurredAtGreaterThanEqualOrderByOccurredAtDescIdDesc(
                any(), any(), any(), any())).thenReturn(List.of());
        var aggregate = mock(PdaOperationalEventRepository.LatencyAggregate.class);
        when(aggregate.getSampleCount()).thenReturn(2L);
        when(aggregate.getAverageLatencyMs()).thenReturn(40d);
        when(aggregate.getP95LatencyMs()).thenReturn(60d);
        when(aggregate.getMaxLatencyMs()).thenReturn(65L);
        when(fixture.events.latency(any(), any())).thenReturn(aggregate);
        when(fixture.terminals.findAllByTiendaIdOrderByNombre(STORE)).thenReturn(List.of(fixture.terminal));
        when(fixture.terminal.getLastSeenAt()).thenReturn(NOW.minusSeconds(3600));

        var result = fixture.service.dashboard(24, 30, 0, 50, null);

        assertThat(result.inactiveTerminals()).extracting(PdaOperationalObservabilityService.InactiveTerminal::terminalId)
                .containsExactly(TERMINAL);
        assertThat(result.performance().p95LatencyMs()).isEqualTo(60d);
        assertThat(result.inactiveTerminals().getFirst().inactiveSince()).isEqualTo(NOW.minusSeconds(1800));
        verify(fixture.terminals).findAllByTiendaIdOrderByNombre(STORE);
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture(UUID storeId, UUID terminalId) {
        var events = mock(PdaOperationalEventRepository.class);
        var work = mock(PdaWorkRepository.class);
        var terminals = mock(TerminalRepository.class);
        var organization = mock(CurrentOrganization.class);
        var authentication = mock(Authentication.class);
        var store = mock(Store.class);
        var terminal = mock(Terminal.class);
        var meters = (ObjectProvider<MeterRegistry>) mock(ObjectProvider.class);
        when(meters.getIfAvailable()).thenReturn(null);
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
        when(authentication.getDetails()).thenReturn(new OperationalSessionContext(UUID.randomUUID(), terminalId, storeId));
        when(terminals.findByIdAndTiendaId(terminalId, storeId)).thenReturn(Optional.of(terminal));
        when(terminal.getId()).thenReturn(terminalId);
        when(terminal.getNombre()).thenReturn("PDA almacen");
        when(terminal.getTipo()).thenReturn(TerminalType.PDA);
        when(terminal.isActiva()).thenReturn(true);
        when(terminal.isAprobada()).thenReturn(true);
        var service = new PdaOperationalObservabilityService(events, work, terminals, organization,
                Clock.fixed(NOW, ZoneOffset.UTC), meters);
        return new Fixture(events, terminals, authentication, terminal, service);
    }

    private record Fixture(PdaOperationalEventRepository events, TerminalRepository terminals,
            Authentication authentication, Terminal terminal, PdaOperationalObservabilityService service) {}
}
