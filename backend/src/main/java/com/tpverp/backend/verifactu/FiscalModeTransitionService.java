package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
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
    private final FiscalStatusSyncPublisher fiscalStatusSyncPublisher;
    private final LicenseRepository licenses;
    private final VerifactuActivationService activation;
    private FiscalIntegrityService integrity;
    private FiscalIntegrityJobRepository integrityJobs;
    private FiscalResponsibleDeclarationService responsibleDeclarations;
    private FiscalOperationalStatusRepository operationalStatus;

    @Autowired
    public FiscalModeTransitionService(CurrentOrganization organization,
            InstallationRepository installations,
            VerifactuConfigurationRepository configurations,
            FiscalModeTransitionRepository transitions,
            FiscalRuntimeProperties runtime,
            FiscalEventService events,
            FiscalStatusSyncPublisher fiscalStatusSyncPublisher,
            LicenseRepository licenses,
            VerifactuActivationService activation) {
        this.organization = organization;
        this.installations = installations;
        this.configurations = configurations;
        this.transitions = transitions;
        this.runtime = runtime;
        this.events = events;
        this.fiscalStatusSyncPublisher = fiscalStatusSyncPublisher;
        this.licenses = licenses;
        this.activation = activation;
    }

    /** Kept for focused service tests and compatibility with existing embedders. */
    public FiscalModeTransitionService(CurrentOrganization organization,
            InstallationRepository installations,
            VerifactuConfigurationRepository configurations,
            FiscalModeTransitionRepository transitions,
            FiscalRuntimeProperties runtime,
            FiscalEventService events) {
        this(organization, installations, configurations, transitions, runtime, events,
                null, null, null);
    }

    /** Compatibility constructor for tests needing the REAL license policy boundary. */
    public FiscalModeTransitionService(CurrentOrganization organization,
            InstallationRepository installations,
            VerifactuConfigurationRepository configurations,
            FiscalModeTransitionRepository transitions,
            FiscalRuntimeProperties runtime,
            FiscalEventService events,
            LicenseRepository licenses,
            VerifactuActivationService activation) {
        this(organization, installations, configurations, transitions, runtime, events,
                null, licenses, activation);
    }

    @Autowired(required = false)
    void setIntegrityService(FiscalIntegrityService integrity) {
        this.integrity = integrity;
    }

    @Autowired(required = false)
    void setIntegrityJobs(FiscalIntegrityJobRepository integrityJobs) {
        this.integrityJobs = integrityJobs;
    }

    @Autowired(required = false)
    void setResponsibleDeclarationService(FiscalResponsibleDeclarationService declarations) {
        this.responsibleDeclarations = declarations;
    }

    @Autowired(required = false)
    void setOperationalStatusRepository(FiscalOperationalStatusRepository operationalStatus) {
        this.operationalStatus = operationalStatus;
    }

    @Transactional(readOnly = true)
    public FiscalStatusView status() {
        var company = organization.currentCompany();
        var currentStore = organization.currentStore();
        var configuration = configurations.findByCompanyId(company.getId()).orElse(null);
        var installation = resolveInstallationOrNull(company.getId());
        var scheduled = configuration != null && installation != null
                ? transitions.findTopByCompanyIdAndInstallationIdAndStatusInOrderByRequestedAtDesc(
                        company.getId(), installation.getId(), java.util.List.of(
                                FiscalModeTransitionStatus.PROGRAMADA,
                                FiscalModeTransitionStatus.FALLIDA))
                        .filter(candidate -> candidate.getStatus() == FiscalModeTransitionStatus.FALLIDA
                                || candidate.getEffectiveAt().isAfter(Instant.now()))
                        .map(this::scheduledView).orElse(null)
                : null;
        var declaration = responsibleDeclarations == null ? null : responsibleDeclarations.status();
        var operations = operationalStatus == null || installation == null
                ? new FiscalOperationalStatusSnapshot(java.util.Map.of(), null, null, 0L)
                : operationalStatus.findForScope(company.getId(), installation.getId());
        return new FiscalStatusView(company.getId(),
                configuration == null ? (runtime.isSandbox() ? runtime.sandboxInitialMode()
                        : FiscalMode.PRE_SIF) : configuration.getCurrentMode(),
                configuration == null ? 0 : configuration.getModeVersion(),
                configuration == null ? null : configuration.getModeSince(),
                runtime.runtimeClass(), runtime.endpointEnvironment(), runtime.transportMode(),
                runtime.productionEnabled(),
                configuration == null ? null : configuration.getVerifactuBlockedUntil(),
                scheduled, currentStore == null ? null : currentStore.getTimezone(),
                runtime.releaseManifest().releaseId(), runtime.systemVersion(),
                runtime.productCapability(),
                declaration == null ? null : declaration.sha256(),
                declaration != null && "AVAILABLE".equals(declaration.status()),
                operations.pendingCount(), operations.oldestPendingAt(),
                operations.lastAeatSuccessAt());
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
        if (target == FiscalMode.NO_VERIFACTU
                && runtime.productCapability() == FiscalProductCapability.VERIFACTU_ONLY) {
            throw new FiscalProductCapabilityViolationException(
                    "La release VERIFACTU_ONLY no permite transiciones a NO_VERIFACTU");
        }
        var company = organization.currentCompany();
        var installation = resolveInstallation(company.getId());
        var normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.isBlank()) {
            throw new IllegalArgumentException("El motivo de la transicion es obligatorio");
        }

        // The preflight emits durable evidence in REQUIRES_NEW transactions
        // and therefore must run before locking this configuration row. The
        // locked read below repeats the authority checks before the transition.
        var observed = configurations.findByCompanyId(company.getId()).orElse(null);
        var observedMode = observed == null ? initialMode() : observed.getCurrentMode();
        if (observed != null && observed.getModeVersion() != expectedVersion) {
            throw new IllegalStateException("La configuracion fiscal ha cambiado; recarga el estado");
        }
        var now = Instant.now();
        if (target == FiscalMode.NO_VERIFACTU
                && runtime.runtimeClass() == FiscalRuntimeClass.REAL) {
            requireNoVerifactuAllowedAt(now, installation.getId());
        }
        if (target == FiscalMode.NO_VERIFACTU
                && (observedMode == FiscalMode.PRE_SIF
                    || (observedMode == FiscalMode.VERIFACTU
                        && runtime.runtimeClass() == FiscalRuntimeClass.REAL))) {
            rejectIfIntegrityJobActive(company.getId(), installation.getId());
            if (observedMode == FiscalMode.VERIFACTU
                    && runtime.runtimeClass() == FiscalRuntimeClass.REAL) {
                validateScheduledExitRequest(observed, verifactuEndDate, aeatAckReference);
            }
            runIntegrityPreflight(observedMode == FiscalMode.VERIFACTU);
        }
        // Initialize only after preflight: an INSERT of a missing configuration
        // would itself hold the row lock until this outer transaction commits.
        configurations.insertIfMissingWithMode(
                java.util.UUID.randomUUID(), company.getId(), initialMode().name());
        var configuration = configurations.findForUpdateByCompanyId(company.getId())
                .orElseThrow(() -> new IllegalStateException("Configuracion fiscal no encontrada"));
        rejectIfIntegrityJobActive(company.getId(), installation.getId());
        if (configuration.getModeVersion() != expectedVersion) {
            throw new IllegalStateException("La configuracion fiscal ha cambiado; recarga el estado");
        }
        var previous = configuration.getCurrentMode();
        if (previous == target) {
            throw new IllegalArgumentException("El modo fiscal ya esta activo");
        }
        if (previous == FiscalMode.VERIFACTU && target == FiscalMode.NO_VERIFACTU
                && runtime.runtimeClass() == FiscalRuntimeClass.REAL) {
            return scheduleRealVerifactuExit(company.getId(), installation.getId(), configuration,
                    expectedVersion, normalizedReason, now, verifactuEndDate, aeatAckReference);
        }
        if (previous == FiscalMode.NO_VERIFACTU && target == FiscalMode.VERIFACTU) {
            // The end event is generated while the SIF is still operating as NO VERI*FACTU.
            events.create(company.getId(), installation.getId(), FiscalMode.NO_VERIFACTU,
                    FiscalEventType.END_NO_VERIFACTU, normalizedReason);
        }
        if (target == FiscalMode.VERIFACTU
                && runtime.runtimeClass() == FiscalRuntimeClass.REAL) {
            activateVerifactu(configuration, installation.getId(), now);
        }
        configuration.changeMode(target, now, null);
        transitions.save(new FiscalModeTransition(company.getId(), installation.getId(), previous,
                target, now, runtime.isSandbox() ? "DEV_SANDBOX" : "ADMIN", normalizedReason,
                expectedVersion));
        if (previous == FiscalMode.PRE_SIF && target == FiscalMode.NO_VERIFACTU) {
            events.create(company.getId(), installation.getId(), FiscalMode.NO_VERIFACTU,
                    FiscalEventType.START_NO_VERIFACTU, normalizedReason);
        }
        if (fiscalStatusSyncPublisher != null) {
            fiscalStatusSyncPublisher.publishCurrent();
        }
        return status();
    }

    private FiscalMode initialMode() {
        return runtime.isSandbox() ? runtime.sandboxInitialMode() : FiscalMode.PRE_SIF;
    }

    private void runIntegrityPreflight(boolean scheduledExit) {
        if (integrity == null) {
            throw new IllegalStateException("Preflight de integridad fiscal no disponible");
        }
        var preflight = integrity.check();
        if (!preflight.ok()) {
            var action = scheduledExit ? "programar la salida VERI*FACTU"
                    : "iniciar NO VERI*FACTU";
            throw new IllegalStateException("No se puede " + action
                    + " con anomalías de integridad: "
                    + String.join(",", preflight.anomalies()));
        }
    }

    private void rejectIfIntegrityJobActive(
            java.util.UUID companyId, java.util.UUID installationId) {
        if (integrityJobs != null
                && integrityJobs.countByCompanyIdAndInstallationIdAndStatusIn(
                        companyId, installationId,
                        java.util.List.of(FiscalIntegrityJobStatus.QUEUED,
                                FiscalIntegrityJobStatus.RUNNING)) > 0) {
            throw new IllegalStateException("fiscal_integrity_job_active");
        }
    }

    private void validateScheduledExitRequest(VerifactuConfiguration configuration,
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
        if (configuration != null && configuration.getVerifactuBlockedUntil() != null
                && verifactuEndDate.isBefore(configuration.getVerifactuBlockedUntil())) {
            throw new IllegalStateException(
                    "FechaFinVeriFactu no puede acortar la permanencia anual VERI*FACTU");
        }
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
        // FechaFinVeriFactu is the last day in VERI*FACTU; the switch is due at
        // the beginning of the following local day.
        var effectiveAt = verifactuEndDate.plusDays(1).atStartOfDay(zone).toInstant();
        requireLicenseAllowsNoVerifactu(installationId, effectiveAt);
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

    private void activateVerifactu(VerifactuConfiguration configuration,
            java.util.UUID installationId, Instant now) {
        var license = activeLicense(installationId);
        if (!license.isOperationalAt(now)) {
            throw new IllegalStateException(
                    "La licencia activa no esta operativa para activar VERI*FACTU");
        }
        var zone = ZoneId.of(organization.currentStore().getTimezone());
        var activationDate = license.getVerifactuActivationDate();
        if (activationDate == null || now.isBefore(activation.activationInstant(
                license.getTaxpayerType(), activationDate, zone))) {
            configuration.activateVoluntarily(now);
        }
    }

    private void requireLicenseAllowsNoVerifactu(
            java.util.UUID installationId, Instant effectiveAt) {
        var license = activeLicense(installationId);
        if (license.getVerifactuActivationDate() == null) {
            return;
        }
        var zone = ZoneId.of(organization.currentStore().getTimezone());
        var mandatoryAt = activation.activationInstant(
                license.getTaxpayerType(), license.getVerifactuActivationDate(), zone);
        if (!effectiveAt.isBefore(mandatoryAt)) {
            throw new IllegalStateException(
                    "La licencia obliga VERI*FACTU en la fecha efectiva solicitada");
        }
    }

    private void requireNoVerifactuAllowedAt(Instant now, java.util.UUID installationId) {
        if (licenses == null || activation == null) {
            return;
        }
        var store = organization.currentStore();
        var license = licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(
                store.getId(), installationId).orElse(null);
        if (license == null || license.getTaxpayerType() == null
                || license.getVerifactuActivationDate() == null) {
            return;
        }
        var mandatoryAt = activation.activationInstant(
                license.getTaxpayerType(), license.getVerifactuActivationDate(),
                ZoneId.of(store.getTimezone()));
        if (!now.isBefore(mandatoryAt)) {
            throw new IllegalStateException(
                    "La licencia obliga VERI*FACTU; no se permite pasar a NO_VERIFACTU");
        }
    }

    private License activeLicense(java.util.UUID installationId) {
        if (licenses == null || activation == null) {
            throw new IllegalStateException(
                    "La politica de licencia VERI*FACTU no esta disponible");
        }
        var store = organization.currentStore();
        var license = licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(
                        store.getId(), installationId)
                .orElseThrow(() -> new IllegalStateException(
                        "No existe una licencia activa para la tienda e instalacion"));
        if (license.getTaxpayerType() == null) {
            throw new IllegalStateException(
                    "La licencia activa no contiene el tipo de obligado fiscal");
        }
        return license;
    }

    private FiscalScheduledTransitionView scheduledView(FiscalModeTransition transition) {
        var scheduled = transition.getStatus() == FiscalModeTransitionStatus.FALLIDA
                && transition.getSourceTransitionId() != null
                ? transitions.findById(transition.getSourceTransitionId()).orElse(transition)
                : transition;
        return new FiscalScheduledTransitionView(scheduled.getPreviousMode(),
                scheduled.getNewMode(), transition.getStatus(), scheduled.getRequestedAt(),
                scheduled.getEffectiveAt(), scheduled.getVerifactuEndDate(),
                scheduled.getAeatAckReference(), transition.getLastErrorCode());
    }

    private com.tpverp.backend.installation.Installation resolveInstallationOrNull(
            java.util.UUID companyId) {
        try {
            return resolveInstallation(companyId);
        } catch (IllegalStateException exception) {
            if ("La instalacion fiscal no esta inicializada".equals(exception.getMessage())) {
                return null;
            }
            throw exception;
        }
    }

    private com.tpverp.backend.installation.Installation resolveInstallation(
            java.util.UUID companyId) {
        return licenses == null
                ? FiscalInstallationResolver.resolveForCompany(companyId, installations, null)
                : FiscalInstallationResolver.resolveCurrent(organization, installations, licenses);
    }
}
