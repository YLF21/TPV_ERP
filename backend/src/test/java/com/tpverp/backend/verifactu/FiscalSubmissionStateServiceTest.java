package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FiscalSubmissionStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-16T12:00:00Z");

    @Mock
    private FiscalSubmissionStateRepository states;
    private FiscalSubmissionState state;
    private FiscalSubmissionStateService service;

    @BeforeEach
    void setUp() {
        state = new FiscalSubmissionState(
                UUID.randomUUID(), FiscalSubmissionStatus.PENDIENTE, NOW.minusSeconds(60));
        lenient().when(states.findById(state.getRecordId())).thenReturn(Optional.of(state));
        lenient().when(states.save(state)).thenReturn(state);
        service = new FiscalSubmissionStateService(
                states, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void marksAcceptedAndClearsPreviousError() {
        service.markRejected(state.getRecordId(), "NIF", "NIF no valido");

        var accepted = service.markAccepted(state.getRecordId());

        assertThat(accepted.getStatus()).isEqualTo(FiscalSubmissionStatus.ACEPTADO);
        assertThat(accepted.getLastErrorCode()).isNull();
        assertThat(accepted.getLastError()).isNull();
        assertThat(accepted.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void storesAeAtRejectionWithoutBlockingSales() {
        var rejected = service.markRejected(
                state.getRecordId(), "NIF_INVALIDO", "El NIF no es correcto");

        assertThat(rejected.getStatus()).isEqualTo(FiscalSubmissionStatus.RECHAZADO);
        assertThat(rejected.getLastErrorCode()).isEqualTo("NIF_INVALIDO");
        assertThat(rejected.getLastError()).isEqualTo("El NIF no es correcto");
    }

    @Test
    void requiresErrorDetailsForVisibleIncidents() {
        assertThatThrownBy(() -> service.markDefective(state.getRecordId(), " ", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("error");

        verify(states, never()).save(state);
    }

    @Test
    void updatesStateWithoutAuthenticatedTenantContext() {
        var sending = service.markSending(state.getRecordId());

        assertThat(sending.getStatus()).isEqualTo(FiscalSubmissionStatus.ENVIANDO);
    }
}
