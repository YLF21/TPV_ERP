package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps the NO VERI*FACTU event chain alive without depending on an interactive session. */
@Component
public class FiscalEventSummaryScheduler {
    private static final Logger log = LoggerFactory.getLogger(FiscalEventSummaryScheduler.class);
    private final VerifactuConfigurationRepository configurations;
    private final InstallationRepository installations;
    private final FiscalEventService events;
    private final FiscalRuntimeProperties runtime;
    private final Clock clock;

    public FiscalEventSummaryScheduler(VerifactuConfigurationRepository configurations,
            InstallationRepository installations, FiscalEventService events,
            FiscalRuntimeProperties runtime, Clock clock) {
        this.configurations = configurations;
        this.installations = installations;
        this.events = events;
        this.runtime = runtime;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${tpv.verifactu.event-summary-interval-ms:21600000}",
            initialDelayString = "${tpv.verifactu.event-summary-initial-delay-ms:21600000}")
    public void emitDueSummaries() {
        // REAL stays disabled until the DEV/AEAT gates have been passed.
        if (!runtime.isSandbox() && !runtime.productionEnabled()) {
            return;
        }
        var installationIds = installations.findAll().stream().map(installation -> installation.getId()).toList();
        for (var configuration : configurations.findAllByCurrentMode(FiscalMode.NO_VERIFACTU)) {
            for (var installationId : installationIds) {
                try {
                    events.createSummaryIfDue(configuration.getCompanyId(), installationId,
                            FiscalMode.NO_VERIFACTU, Instant.now(clock));
                } catch (RuntimeException exception) {
                    log.warn("No se pudo generar el resumen fiscal NO VERI*FACTU para {} / {}",
                            configuration.getCompanyId(), installationId, exception);
                }
            }
        }
    }
}
