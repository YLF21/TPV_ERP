package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.LicenseRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Flushes the NO VERI*FACTU event summary during an orderly Spring shutdown.
 *
 * SmartLifecycle.stop is called while the application context is still alive,
 * so the transactional event service and its persistence dependencies remain
 * usable. A low phase lets scheduled workers stop before this final flush.
 */
@Component
public final class FiscalEventShutdownLifecycle implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(FiscalEventShutdownLifecycle.class);
    private static final int SHUTDOWN_PHASE = Integer.MIN_VALUE;

    private final VerifactuConfigurationRepository configurations;
    private final InstallationRepository installations;
    private final LicenseRepository licenses;
    private final FiscalEventService events;
    private final Clock clock;
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();
    private volatile boolean running;

    public FiscalEventShutdownLifecycle(VerifactuConfigurationRepository configurations,
            InstallationRepository installations, LicenseRepository licenses,
            FiscalEventService events, Clock clock) {
        this.configurations = configurations;
        this.installations = installations;
        this.licenses = licenses;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        stop(() -> { });
    }

    @Override
    public void stop(Runnable callback) {
        try {
            if (shutdownStarted.compareAndSet(false, true)) {
                flushSummaries();
            }
        } finally {
            running = false;
            if (callback != null) {
                callback.run();
            }
        }
    }

    private void flushSummaries() {
        var shutdownAt = Instant.now(clock);
        final var noVerifactuConfigurations = findNoVerifactuConfigurations();
        for (var configuration : noVerifactuConfigurations) {
            try {
                var installation = FiscalInstallationResolver.resolveForCompany(
                        configuration.getCompanyId(), installations, licenses);
                events.createSummaryBeforeShutdown(configuration.getCompanyId(),
                        installation.getId(), FiscalMode.NO_VERIFACTU, shutdownAt);
            } catch (RuntimeException exception) {
                // One unresolved tenant must not prevent the remaining tenants
                // from flushing their own event chains during process shutdown.
                LOG.error("No se pudo guardar el resumen fiscal antes del apagado para {}",
                        configuration.getCompanyId(), exception);
            }
        }
    }

    private java.util.List<VerifactuConfiguration> findNoVerifactuConfigurations() {
        try {
            return configurations.findAllByCurrentMode(FiscalMode.NO_VERIFACTU);
        } catch (RuntimeException exception) {
            LOG.error("No se pudieron consultar las empresas NO VERI*FACTU antes del apagado",
                    exception);
            return java.util.List.of();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return SHUTDOWN_PHASE;
    }
}
