package com.tpverp.backend.verifactu;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists a failed scheduled transition after the application TX rolls back. */
@Service
public class FiscalModeTransitionFailureRecorder {

    private static final int MAX_ERROR_LENGTH = 2000;
    private final FiscalModeTransitionRepository transitions;

    public FiscalModeTransitionFailureRecorder(FiscalModeTransitionRepository transitions) {
        this.transitions = transitions;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            FiscalModeTransition scheduled, Instant failedAt, RuntimeException exception) {
        var detail = exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName() : exception.getMessage().trim();
        if (detail.length() > MAX_ERROR_LENGTH) {
            detail = detail.substring(0, MAX_ERROR_LENGTH);
        }
        transitions.save(FiscalModeTransition.failed(
                scheduled, failedAt, "TRANSITION_APPLICATION_FAILED", detail));
    }
}
