package com.tpverp.backend.verifactu;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiscalSubmissionStateService {

    private final FiscalSubmissionStateRepository states;
    private final Clock clock;

    public FiscalSubmissionStateService(
            FiscalSubmissionStateRepository states,
            Clock clock) {
        this.states = states;
        this.clock = clock;
    }

    @Transactional
    public FiscalSubmissionState markSending(UUID recordId) {
        return mark(recordId, FiscalSubmissionStatus.ENVIANDO);
    }

    @Transactional
    public FiscalSubmissionState markSent(UUID recordId) {
        return mark(recordId, FiscalSubmissionStatus.ENVIADO);
    }

    @Transactional
    public FiscalSubmissionState markSent(UUID recordId, UUID claimToken) {
        return markClaimed(recordId, FiscalSubmissionStatus.ENVIADO, null, null, claimToken);
    }

    @Transactional
    public FiscalSubmissionState markAccepted(UUID recordId) {
        return mark(recordId, FiscalSubmissionStatus.ACEPTADO);
    }

    @Transactional
    public FiscalSubmissionState markAccepted(UUID recordId, UUID claimToken) {
        return markClaimed(recordId, FiscalSubmissionStatus.ACEPTADO, null, null, claimToken);
    }

    @Transactional
    public FiscalSubmissionState markSubsanado(UUID recordId) {
        return mark(recordId, FiscalSubmissionStatus.SUBSANADO);
    }

    @Transactional
    public FiscalSubmissionState markAcceptedWithErrors(
            UUID recordId, String errorCode, String error) {
        return markIncident(
                recordId, FiscalSubmissionStatus.ACEPTADO_CON_ERRORES, errorCode, error);
    }

    @Transactional
    public FiscalSubmissionState markAcceptedWithErrors(
            UUID recordId, String errorCode, String error, UUID claimToken) {
        return markClaimed(recordId, FiscalSubmissionStatus.ACEPTADO_CON_ERRORES,
                errorCode, error, claimToken);
    }

    @Transactional
    public FiscalSubmissionState markRejected(
            UUID recordId, String errorCode, String error) {
        return markIncident(recordId, FiscalSubmissionStatus.RECHAZADO, errorCode, error);
    }

    @Transactional
    public FiscalSubmissionState markRejected(
            UUID recordId, String errorCode, String error, UUID claimToken) {
        return markClaimed(recordId, FiscalSubmissionStatus.RECHAZADO,
                errorCode, error, claimToken);
    }

    @Transactional
    public FiscalSubmissionState markDefective(
            UUID recordId, String errorCode, String error) {
        return markIncident(recordId, FiscalSubmissionStatus.DEFECTUOSO, errorCode, error);
    }

    @Transactional
    public FiscalSubmissionState markDefective(
            UUID recordId, String errorCode, String error, UUID claimToken) {
        return markClaimed(recordId, FiscalSubmissionStatus.DEFECTUOSO,
                errorCode, error, claimToken);
    }

    @Transactional
    public FiscalSubmissionState markTransportFailure(
            UUID recordId, String errorCode, String error, UUID claimToken) {
        var state = claimedState(recordId);
        state.markRetryableFailureWithClaim(errorCode, error, now(), claimToken);
        return states.save(state);
    }

    @Transactional
    public void requireClaim(UUID recordId, UUID claimToken) {
        // The token check must observe and serialize against the same row
        // mutation that records the request; a plain read permits a stale
        // worker to persist after a lease has been reclaimed.
        var state = states.findForUpdate(recordId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "estado de envio fiscal no encontrado"));
        if (!state.isOwnedBy(claimToken, clock.instant())) {
            throw new IllegalStateException("El worker ya no posee el registro fiscal");
        }
    }

    protected FiscalSubmissionState mark(UUID recordId, FiscalSubmissionStatus status) {
        var state = state(recordId);
        state.mark(status, now());
        return states.save(state);
    }

    protected FiscalSubmissionState markIncident(
            UUID recordId,
            FiscalSubmissionStatus status,
            String errorCode,
            String error) {
        var state = state(recordId);
        state.markIncident(status, errorCode, error, now());
        return states.save(state);
    }

    private FiscalSubmissionState markClaimed(
            UUID recordId,
            FiscalSubmissionStatus status,
            String errorCode,
            String error,
            UUID claimToken) {
        var state = claimedState(recordId);
        state.markWithClaim(status, errorCode, error, now(), claimToken);
        return states.save(state);
    }

    private FiscalSubmissionState state(UUID recordId) {
        return states.findById(recordId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "estado de envio fiscal no encontrado"));
    }

    private FiscalSubmissionState claimedState(UUID recordId) {
        return states.findForUpdate(recordId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "estado de envio fiscal no encontrado"));
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
