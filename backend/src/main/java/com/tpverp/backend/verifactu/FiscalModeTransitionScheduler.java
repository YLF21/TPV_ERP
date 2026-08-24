package com.tpverp.backend.verifactu;

import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Applies due REAL transitions exactly once and emits the NO VERI*FACTU start event. */
@Component
public class FiscalModeTransitionScheduler {
    private final FiscalModeTransitionRepository transitions;
    private final VerifactuConfigurationRepository configurations;
    private final FiscalEventService events;
    private final FiscalRuntimeProperties runtime;

    public FiscalModeTransitionScheduler(FiscalModeTransitionRepository transitions,
            VerifactuConfigurationRepository configurations, FiscalEventService events,
            FiscalRuntimeProperties runtime) {
        this.transitions = transitions;
        this.configurations = configurations;
        this.events = events;
        this.runtime = runtime;
    }

    @Scheduled(fixedDelayString = "${tpv.verifactu.mode-transition-worker-delay-ms:60000}")
    public void tick() {
        if (runtime.runtimeClass() == FiscalRuntimeClass.REAL) {
            applyDue(Instant.now());
        }
    }

    @Transactional
    public int applyDue(Instant now) {
        if (runtime.runtimeClass() != FiscalRuntimeClass.REAL) {
            return 0;
        }
        var due = transitions.findDueWithoutAppliedTransition(
                FiscalModeTransitionStatus.PROGRAMADA, FiscalModeTransitionStatus.APLICADA, now);
        var applied = 0;
        for (var scheduled : due) {
            var configuration = configurations.findForUpdateByCompanyId(scheduled.getCompanyId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Configuracion fiscal no encontrada para transicion programada"));
            if (configuration.getModeVersion() != scheduled.getExpectedVersion()
                    || configuration.getCurrentMode() != FiscalMode.VERIFACTU) {
                throw new IllegalStateException(
                        "La transicion fiscal programada ya no coincide con el modo/version actual");
            }
            configuration.changeMode(FiscalMode.NO_VERIFACTU, now, null);
            configurations.save(configuration);
            transitions.save(new FiscalModeTransition(scheduled.getCompanyId(),
                    scheduled.getInstallationId(), FiscalMode.VERIFACTU,
                    FiscalMode.NO_VERIFACTU, now, "SCHEDULED_WORKER",
                    "Aplicacion de FechaFinVeriFactu " + scheduled.getVerifactuEndDate(),
                    scheduled.getExpectedVersion()));
            events.create(scheduled.getCompanyId(), scheduled.getInstallationId(),
                    FiscalMode.NO_VERIFACTU, FiscalEventType.START_NO_VERIFACTU,
                    "Salida VERI*FACTU; ACK " + scheduled.getAeatAckReference());
            applied++;
        }
        return applied;
    }
}
