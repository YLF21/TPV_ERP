package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
        var installation = installations.findAll().stream().findFirst().orElse(null);
        var scheduled = configuration != null && installation != null
                ? transitions.findTopByCompanyIdAndInstallationIdAndStatusOrderByRequestedAtDesc(
                        company.getId(), installation.getId(), FiscalModeTransitionStatus.PROGRAMADA)
                        .filter(candidate -> candidate.getEffectiveAt().isAfter(Instant.now()))
                        .map(this::scheduledView).orElse(null)
                : null;
        return new FiscalStatusView(company.getId(),
                configuration == null ? (runtime.isSandbox() ? runtime.sandboxInitialMode()
                        : FiscalMode.PRE_SIF) : configuration.getCurrentMode(),
                configuration == null ? 0 : configuration.getModeVersion(),
                configuration == null ? null : configuration.getModeSince(),
                runtime.runtimeClass(), runtime.endpointEnvironment(), runtime.transportMode(),
                runtime.productionEnabled(),
                configuration == null ? null : configuration.getVerifactuBlockedUntil(),
                scheduled);
    }

    @Transactional
    public FiscalStatusView transition(FiscalMode target, long expectedVersion,
            String reason, boolean confirmation) {
        return transition(target, expectedVersion, reason, confirmation, null, null);
    }

    @Transactional
    public FiscalStatusView transition(FiscalMode target, long expectedVersion,
            String reason, boolean confirmation, LocalDate verifactuEndDate,
            String aeatAckReference) {
        if (!confirmation) {
            throw new IllegalArgumentException("La confirmacion explicita es obligatoria");
        }
        if (target == null || target == FiscalMode.PRE_SIF) {
            throw new IllegalArgumentException("PRE_SIF solo se obtiene al iniciar un laboratorio nuevo");
        }
        var company = organization.currentCompany();
        var installation = installations.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Instalacion no encontrada"));
        configurations.insertIfMissingWithMode(
                java.util.UUID.randomUUID(), company.getId(), initialMode().name());
        var configuration = configurations.findForUpdateByCompanyId(company.getId())
                .orElseThrow(() -> new IllegalStateException("Configuracion fiscal no encontrada"));
        if (configuration.getModeVersion() != expectedVersion) {
            throw new IllegalStateException("La configuracion fiscal ha cambiado; recarga el estado");
        }
        var previous = configuration.getCurrentMode();
        if (previous == target) {
            throw new IllegalArgumentException("El modo fiscal ya esta activo");
        }
        var normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.isBlank()) {
            throw new IllegalArgumentException("El motivo de la transicion es obligatorio");
        }
        var now = Instant.now();
        if (previous == FiscalMode.VERIFACTU && target == FiscalMode.NO_VERIFACTU
                && runtime.runtimeClass() == FiscalRuntimeClass.REAL) {
            return scheduleRealVerifactuExit(company.getId(), installation.getId(), configuration,
                    expectedVersion, normalizedReason, now, verifactuEndDate, aeatAckReference);
        }
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

    private FiscalMode initialMode() {
        return runtime.isSandbox() ? runtime.sandboxInitialMode() : FiscalMode.PRE_SIF;
    }

    private FiscalStatusView scheduleRealVerifactuExit(java.util.UUID companyId,
            java.util.UUID installationId, VerifactuConfiguration configuration,
            long expectedVersion, String reason, Instant requestedAt,
            LocalDate verifactuEndDate, String aeatAckReference) {
        if (verifactuEndDate == null || aeatAckReference == null
                || aeatAckReference.trim().isBlank()) {
            throw new IllegalArgumentException(
                    "VERI*FACTU a NO requiere FechaFinVeriFactu y ACK AEAT");
        }
        var zone = ZoneId.of(organization.currentStore().getTimezone());
        if (!verifactuEndDate.isAfter(LocalDate.now(zone))) {
            throw new IllegalArgumentException(
                    "FechaFinVeriFactu debe pertenecer a un periodo futuro");
        }
        var blockedUntil = configuration.getVerifactuBlockedUntil();
        if (blockedUntil != null && verifactuEndDate.isBefore(blockedUntil)) {
            throw new IllegalStateException(
                    "FechaFinVeriFactu no puede acortar la permanencia anual VERI*FACTU");
        }
        if (integrity == null) {
            throw new IllegalStateException("Preflight de integridad fiscal no disponible");
        }
        var preflight = integrity.check();
        if (!preflight.ok()) {
            throw new IllegalStateException(
                    "No se puede programar la salida VERI*FACTU con anomalías de integridad: "
                            + String.join(",", preflight.anomalies()));
        }
        // FechaFinVeriFactu is the last day in VERI*FACTU; the switch is due at
        // the beginning of the following local day.
        var effectiveAt = verifactuEndDate.plusDays(1).atStartOfDay(zone).toInstant();
        var existing = transitions.findTopByCompanyIdAndInstallationIdAndStatusOrderByRequestedAtDesc(
                companyId, installationId, FiscalModeTransitionStatus.PROGRAMADA);
        if (existing.isPresent() && existing.get().getEffectiveAt().isAfter(Instant.now())) {
            throw new IllegalStateException("Ya existe una salida VERI*FACTU programada");
        }
        transitions.save(new FiscalModeTransition(companyId, installationId,
                FiscalMode.VERIFACTU, FiscalMode.NO_VERIFACTU, requestedAt, effectiveAt,
                runtime.isSandbox() ? "DEV_SANDBOX" : "ADMIN", reason, expectedVersion,
                verifactuEndDate, aeatAckReference.trim()));
        return status();
    }

    private FiscalScheduledTransitionView scheduledView(FiscalModeTransition transition) {
        return new FiscalScheduledTransitionView(transition.getPreviousMode(),
                transition.getNewMode(), transition.getStatus(), transition.getRequestedAt(),
                transition.getEffectiveAt(), transition.getVerifactuEndDate(),
                transition.getAeatAckReference());
    }
}
