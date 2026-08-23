package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiscalModeTransitionService {
    private final CurrentOrganization organization;
    private final InstallationRepository installations;
    private final VerifactuConfigurationRepository configurations;
    private final FiscalModeTransitionRepository transitions;
    private final FiscalRuntimeProperties runtime;
    private final FiscalEventService events;
    private FiscalIntegrityService integrity;

    public FiscalModeTransitionService(CurrentOrganization organization,
            InstallationRepository installations,
            VerifactuConfigurationRepository configurations,
            FiscalModeTransitionRepository transitions,
            FiscalRuntimeProperties runtime,
            FiscalEventService events) {
        this.organization = organization;
        this.installations = installations;
        this.configurations = configurations;
        this.transitions = transitions;
        this.runtime = runtime;
        this.events = events;
    }

    @Autowired(required = false)
    void setIntegrityService(FiscalIntegrityService integrity) {
        this.integrity = integrity;
    }

    @Transactional(readOnly = true)
    public FiscalStatusView status() {
        var company = organization.currentCompany();
        var configuration = configurations.findByCompanyId(company.getId()).orElse(null);
        return new FiscalStatusView(company.getId(),
                configuration == null ? (runtime.isSandbox() ? runtime.sandboxInitialMode()
                        : FiscalMode.PRE_SIF) : configuration.getCurrentMode(),
                configuration == null ? 0 : configuration.getModeVersion(),
                configuration == null ? null : configuration.getModeSince(),
                runtime.runtimeClass(), runtime.endpointEnvironment(), runtime.transportMode(),
                runtime.productionEnabled(),
                configuration == null ? null : configuration.getVerifactuBlockedUntil());
    }

    @Transactional
    public FiscalStatusView transition(FiscalMode target, long expectedVersion,
            String reason, boolean confirmation) {
        if (!confirmation) {
            throw new IllegalArgumentException("La confirmacion explicita es obligatoria");
        }
        if (target == null || target == FiscalMode.PRE_SIF) {
            throw new IllegalArgumentException("PRE_SIF solo se obtiene al iniciar un laboratorio nuevo");
        }
        var company = organization.currentCompany();
        var installation = installations.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Instalacion no encontrada"));
        configurations.insertIfMissing(java.util.UUID.randomUUID(), company.getId());
        var configuration = configurations.findForUpdateByCompanyId(company.getId())
                .orElseThrow(() -> new IllegalStateException("Configuracion fiscal no encontrada"));
        if (configuration.getModeVersion() != expectedVersion) {
            throw new IllegalStateException("La configuracion fiscal ha cambiado; recarga el estado");
        }
        var previous = configuration.getCurrentMode();
        if (previous == target) {
            throw new IllegalArgumentException("El modo fiscal ya esta activo");
        }
        if (previous == FiscalMode.VERIFACTU && target == FiscalMode.NO_VERIFACTU
                && runtime.runtimeClass() == FiscalRuntimeClass.REAL) {
            throw new IllegalStateException("El cambio VERI*FACTU a NO requiere periodo legal y ACK AEAT");
        }
        var normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.isBlank()) {
            throw new IllegalArgumentException("El motivo de la transicion es obligatorio");
        }
        var now = Instant.now();
        if (previous == FiscalMode.PRE_SIF && target == FiscalMode.NO_VERIFACTU) {
            if (integrity == null) {
                throw new IllegalStateException("Preflight de integridad fiscal no disponible");
            }
            var preflight = integrity.check();
            if (!preflight.ok()) {
                throw new IllegalStateException(
                        "No se puede iniciar NO VERI*FACTU con anomalías de integridad: "
                                + String.join(",", preflight.anomalies()));
            }
        }
        if (previous == FiscalMode.NO_VERIFACTU && target == FiscalMode.VERIFACTU) {
            // The end event is generated while the SIF is still operating as NO VERI*FACTU.
            events.create(company.getId(), installation.getId(), FiscalMode.NO_VERIFACTU,
                    FiscalEventType.END_NO_VERIFACTU, normalizedReason);
        }
        configuration.changeMode(target, now, null);
        transitions.save(new FiscalModeTransition(company.getId(), installation.getId(), previous,
                target, now, runtime.isSandbox() ? "DEV_SANDBOX" : "ADMIN", normalizedReason,
                expectedVersion));
        if (previous == FiscalMode.PRE_SIF && target == FiscalMode.NO_VERIFACTU) {
            events.create(company.getId(), installation.getId(), FiscalMode.NO_VERIFACTU,
                    FiscalEventType.START_NO_VERIFACTU, normalizedReason);
        }
        return status();
    }
}
