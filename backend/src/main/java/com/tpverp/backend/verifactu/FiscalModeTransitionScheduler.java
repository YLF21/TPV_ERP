package com.tpverp.backend.verifactu;

import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.StoreRepository;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Applies due REAL transitions exactly once and emits the NO VERI*FACTU start event. */
@Component
public class FiscalModeTransitionScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(FiscalModeTransitionScheduler.class);
    private final FiscalModeTransitionRepository transitions;
    private final FiscalRuntimeProperties runtime;
    private final FiscalModeTransitionExecutor executor;
    private final FiscalModeTransitionFailureRecorder failures;

    @Autowired
    public FiscalModeTransitionScheduler(FiscalModeTransitionRepository transitions,
            FiscalRuntimeProperties runtime, FiscalModeTransitionExecutor executor,
            FiscalModeTransitionFailureRecorder failures) {
        this.transitions = transitions;
        this.runtime = runtime;
        this.executor = executor;
        this.failures = failures;
    }

    /** Compatibility constructor used by focused unit tests. */
    public FiscalModeTransitionScheduler(FiscalModeTransitionRepository transitions,
            VerifactuConfigurationRepository configurations, FiscalEventService events,
            FiscalRuntimeProperties runtime) {
        this.transitions = transitions;
        this.runtime = runtime;
        this.executor = new FiscalModeTransitionExecutor(transitions, configurations, events);
        this.failures = new FiscalModeTransitionFailureRecorder(transitions);
    }

    @Autowired
    void setLicensePolicy(LicenseRepository licenses, StoreRepository stores,
            VerifactuActivationService activation) {
        executor.setLicensePolicy(licenses, stores, activation);
    }

    @Scheduled(fixedDelayString = "${tpv.verifactu.mode-transition-worker-delay-ms:60000}")
    public void tick() {
        if (runtime.runtimeClass() == FiscalRuntimeClass.REAL) {
            applyDue(Instant.now());
        }
    }

    public int applyDue(Instant now) {
        if (runtime.runtimeClass() != FiscalRuntimeClass.REAL) {
            return 0;
        }
        var due = transitions.findDueWithoutAppliedTransition(
                FiscalModeTransitionStatus.PROGRAMADA, FiscalModeTransitionStatus.APLICADA,
                FiscalModeTransitionStatus.FALLIDA, now);
        var applied = 0;
        for (var scheduled : due) {
            try {
                if (executor.apply(scheduled.getId(), now)) {
                    applied++;
                }
            } catch (RuntimeException exception) {
                try {
                    failures.record(scheduled, now, exception);
                } catch (RuntimeException persistenceException) {
                    LOG.error("No se pudo persistir el fallo de la transicion fiscal {}: {}",
                            scheduled.getId(), persistenceException.getMessage());
                }
                LOG.error("No se pudo aplicar la transicion fiscal programada {}: {}",
                        scheduled.getId(), exception.getMessage());
            }
        }
        return applied;
    }
}
