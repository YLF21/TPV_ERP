package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import java.util.UUID;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VerifactuManualRetryServiceTest {

    @Mock
    private FiscalSubmissionQueueService queue;
    @Mock
    private VerifactuSubmissionService submissions;
    @Mock
    private AuditService audit;
    @Mock
    private FiscalRecord record;
    @Mock
    private FiscalSubmissionState state;

    @Test
    void retriesClaimedRecordAndReturnsOnlySanitizedOutcome() {
        var recordId = UUID.randomUUID();
        var request = new VerifactuManualRetryRequest("  Incidencia de red revisada  ", 4L);
        when(queue.claimForManualRetry(recordId, 4L))
                .thenReturn(new ClaimedFiscalSubmission(record, state));
        when(submissions.submit(record)).thenReturn(new VerifactuSubmissionResult(
                FiscalSubmissionStatus.ACEPTADO,
                null,
                "Detalle que no debe salir en la respuesta",
                "<respuesta>protegida</respuesta>"));

        var result = service().retry(recordId, request);

        assertThat(result).isEqualTo(new VerifactuManualRetryView(
                recordId, FiscalSubmissionStatus.ACEPTADO, null));
        verify(audit).record(
                eq("VERIFACTU_MANUAL_RETRY_EXECUTED"),
                eq(AuditResult.EXITO),
                argThat(details ->
                        recordId.toString().equals(details.get("recordId"))
                                && "Incidencia de red revisada".equals(details.get("reason"))
                                && "ACEPTADO".equals(details.get("resultStatus"))
                                && !details.containsKey("error")));
    }

    @Test
    void auditsRejectedClaimWithoutLeakingExceptionMessage() {
        var recordId = UUID.randomUUID();
        var request = new VerifactuManualRetryRequest("Reintento solicitado", 2L);
        when(queue.claimForManualRetry(recordId, 2L))
                .thenThrow(new IllegalStateException("Mensaje interno sensible"));

        assertThatThrownBy(() -> service().retry(recordId, request))
                .isInstanceOf(IllegalStateException.class);
        verify(audit).record(
                eq("VERIFACTU_MANUAL_RETRY_REJECTED"),
                eq(AuditResult.FALLO),
                argThat(details ->
                        "IllegalStateException".equals(details.get("cause"))
                                && !details.containsValue("Mensaje interno sensible")));
    }

    @Test
    void retryManualCoordinatedUsesScopedBatchAndNeverSingleSubmit() {
        var recordId = UUID.randomUUID();
        var request = new VerifactuManualRetryRequest("Reintento con scope", 3L);
        var scope = new FiscalSubmissionScopeFlow(
                UUID.randomUUID(), UUID.randomUUID(), FiscalEndpointEnvironment.TEST);
        var batch = new ClaimedFiscalBatch(scope,
                List.of(new ClaimedFiscalSubmission(record, state)));
        var outcome = new VerifactuSubmissionResult(
                FiscalSubmissionStatus.ACEPTADO, null, null, "respuesta", true);
        when(queue.batchCoordinatorAvailable()).thenReturn(true);
        when(queue.claimManualRetryBatch(recordId, 3L, 1000))
                .thenReturn(Optional.of(batch));
        when(submissions.submitBatch(batch)).thenReturn(new VerifactuBatchSubmissionResult(
                true, FiscalSubmissionStatus.ACEPTADO, List.of(outcome), 60, true, null, null));

        var result = service().retry(recordId, request);

        assertThat(result.status()).isEqualTo(FiscalSubmissionStatus.ACEPTADO);
        verify(submissions).submitBatch(batch);
        verify(submissions, never()).submit(record, state.getClaimToken());
        verify(queue, never()).claimForManualRetry(recordId, 3L);
    }

    private VerifactuManualRetryService service() {
        return new VerifactuManualRetryService(queue, submissions, audit);
    }
}
