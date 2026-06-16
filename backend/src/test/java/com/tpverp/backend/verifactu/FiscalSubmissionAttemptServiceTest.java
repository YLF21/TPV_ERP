package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FiscalSubmissionAttemptServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-16T12:30:00Z");

    @Mock
    private FiscalSubmissionAttemptRepository attempts;
    @Mock
    private FiscalSubmissionStateService states;

    private FiscalSubmissionAttemptService service;
    private UUID recordId;

    @BeforeEach
    void setUp() {
        recordId = UUID.randomUUID();
        when(attempts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new FiscalSubmissionAttemptService(
                attempts, states, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void recordsSentAttemptAndMarksStateAsSent() {
        var attempt = service.recordSent(recordId, "<xml/>");

        assertThat(attempt.getStatus()).isEqualTo(FiscalSubmissionStatus.ENVIADO);
        assertThat(attempt.getRequestXml()).isEqualTo("<xml/>");
        assertThat(attempt.getAttemptedAt()).isEqualTo(NOW);
        verify(states).markSent(recordId);
    }

    @Test
    void recordsRejectedAttemptAndMarksStateAsRejected() {
        var attempt = service.recordRejected(
                recordId, "NIF_INVALIDO", "NIF no valido", "<response/>");

        assertThat(attempt.getStatus()).isEqualTo(FiscalSubmissionStatus.RECHAZADO);
        assertThat(attempt.getErrorCode()).isEqualTo("NIF_INVALIDO");
        assertThat(attempt.getError()).isEqualTo("NIF no valido");
        assertThat(attempt.getResponsePayload()).isEqualTo("<response/>");
        verify(states).markRejected(recordId, "NIF_INVALIDO", "NIF no valido");
    }

    @Test
    void returnsHistoryNewestFirst() {
        var attempt = service.recordSent(recordId, "<xml/>");
        when(attempts.findAllByRecordIdOrderByAttemptedAtDesc(recordId))
                .thenReturn(List.of(attempt));

        assertThat(service.history(recordId)).containsExactly(attempt);
    }

    @Test
    void trimsStoredErrorDetails() {
        service.recordDefective(recordId, " CAMPO ", " Dato invalido ", "<response/>");

        var saved = ArgumentCaptor.forClass(FiscalSubmissionAttempt.class);
        verify(attempts).save(saved.capture());
        assertThat(saved.getValue().getErrorCode()).isEqualTo("CAMPO");
        assertThat(saved.getValue().getError()).isEqualTo("Dato invalido");
        verify(states).markDefective(recordId, "CAMPO", "Dato invalido");
    }
}
