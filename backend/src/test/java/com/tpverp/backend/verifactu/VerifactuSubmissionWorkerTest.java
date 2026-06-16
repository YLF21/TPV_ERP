package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VerifactuSubmissionWorkerTest {

    @Mock private FiscalSubmissionQueueService queue;
    @Mock private VerifactuSubmissionService submissions;

    private FiscalRecord record;
    private VerifactuSubmissionWorker worker;

    @BeforeEach
    void setUp() {
        record = record();
        worker = new VerifactuSubmissionWorker(queue, submissions);
    }

    @Test
    void noHaceNadaSiNoHayRegistrosPendientes() {
        when(queue.claimNext()).thenReturn(Optional.empty());

        var result = worker.processNext();

        assertThat(result.processed()).isFalse();
        verify(submissions, never()).submit(record);
    }

    @Test
    void reclamaYEnviaUnRegistroPendiente() {
        when(queue.claimNext()).thenReturn(Optional.of(new ClaimedFiscalSubmission(
                record, new FiscalSubmissionState(record.getId(), FiscalSubmissionStatus.ENVIANDO, Instant.now()))));
        when(submissions.submit(record)).thenReturn(new VerifactuSubmissionResult(
                FiscalSubmissionStatus.ACEPTADO, null, null, "<ok/>"));

        var result = worker.processNext();

        assertThat(result.processed()).isTrue();
        assertThat(result.status()).isEqualTo(FiscalSubmissionStatus.ACEPTADO);
        verify(submissions).submit(record);
    }

    private static FiscalRecord record() {
        return new FiscalRecord(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, FiscalRecordOperation.ALTA, FiscalDocumentType.F2,
                "001-260616-000001", LocalDate.of(2026, 6, 16),
                Instant.parse("2026-06-16T10:00:00Z"), "Atlantic/Canary",
                "B12345674", new BigDecimal("2.10"), new BigDecimal("12.10"),
                null, "A".repeat(64), "B".repeat(64), Map.of("baseTotal", BigDecimal.TEN),
                "1.0", "SHA-256", "0.0.1");
    }
}
