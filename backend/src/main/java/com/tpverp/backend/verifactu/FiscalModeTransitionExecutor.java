package com.tpverp.backend.verifactu;

import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.StoreRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Applies one scheduled transition in an independent transaction. */
@Component
public class FiscalModeTransitionExecutor {
    private final FiscalModeTransitionRepository transitions;
    private final VerifactuConfigurationRepository configurations;
    private final FiscalEventService events;
    private LicenseRepository licenses;
    private StoreRepository stores;
    private VerifactuActivationService activation;

    @Autowired
    public FiscalModeTransitionExecutor(
            FiscalModeTransitionRepository transitions,
            VerifactuConfigurationRepository configurations,
            FiscalEventService events,
            LicenseRepository licenses,
            StoreRepository stores,
            VerifactuActivationService activation) {
        this.transitions = transitions;
        this.configurations = configurations;
        this.events = events;
        this.licenses = licenses;
        this.stores = stores;
        this.activation = activation;
    }

    FiscalModeTransitionExecutor(
            FiscalModeTransitionRepository transitions,
            VerifactuConfigurationRepository configurations,
            FiscalEventService events) {
        this.transitions = transitions;
        this.configurations = configurations;
        this.events = events;
    }

    void setLicensePolicy(
            LicenseRepository licenses,
            StoreRepository stores,
            VerifactuActivationService activation) {
        this.licenses = licenses;
        this.stores = stores;
        this.activation = activation;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean apply(UUID transitionId, Instant now) {
        var scheduled = transitions.findById(transitionId)
                .orElseThrow(() -> new IllegalStateException("Transicion fiscal programada no encontrada"));
        if (scheduled.getStatus() != FiscalModeTransitionStatus.PROGRAMADA
                || scheduled.getEffectiveAt().isAfter(now)) {
            return false;
        }
        var configuration = configurations.findForUpdateByCompanyId(scheduled.getCompanyId())
                .orElseThrow(() -> new IllegalStateException(
                        "Configuracion fiscal no encontrada para transicion programada"));
        // The configuration row serializes workers for the same company. Once the
        // lock is acquired, a second worker must observe the immutable APLICADA row
        // committed by the first worker and treat the replay as successful/no-op.
        if (transitions.countAppliedTransitionsForSchedule(
                scheduled.getCompanyId(), scheduled.getInstallationId(),
                FiscalModeTransitionStatus.APLICADA, scheduled.getPreviousMode(),
                scheduled.getNewMode(), scheduled.getExpectedVersion(),
                scheduled.getEffectiveAt()) > 0) {
            return false;
        }
        if (configuration.getModeVersion() != scheduled.getExpectedVersion()
                || configuration.getCurrentMode() != FiscalMode.VERIFACTU) {
            throw new IllegalStateException(
                    "La transicion fiscal programada ya no coincide con el modo/version actual");
        }
        requireLicenseAllowsNoVerifactu(scheduled, now);
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
        return true;
    }

    private void requireLicenseAllowsNoVerifactu(
            FiscalModeTransition scheduled, Instant appliedAt) {
        if (licenses == null || stores == null || activation == null) {
            throw new IllegalStateException(
                    "La politica de licencia VERI*FACTU no esta disponible");
        }
        var candidates = licenses.findActiveByCompanyIdAndInstallationId(
                scheduled.getCompanyId(), scheduled.getInstallationId());
        if (candidates.size() != 1) {
            throw new IllegalStateException(candidates.isEmpty()
                    ? "No existe licencia activa para revalidar la salida VERI*FACTU"
                    : "Existen varias licencias activas para revalidar la salida VERI*FACTU");
        }
        License license = candidates.getFirst();
        if (license.getTaxpayerType() == null || license.getVerifactuActivationDate() == null) {
            throw new IllegalStateException(
                    "La licencia activa no contiene la politica de licencia VERI*FACTU");
        }
        var store = stores.findWithCompanyById(license.getTiendaId())
                .orElseThrow(() -> new IllegalStateException(
                        "Tienda de la licencia fiscal no encontrada"));
        if (!store.getEmpresa().getId().equals(scheduled.getCompanyId())) {
            throw new IllegalStateException(
                    "La licencia no pertenece a la empresa de la transicion fiscal");
        }
        var mandatoryAt = activation.activationInstant(
                license.getTaxpayerType(), license.getVerifactuActivationDate(),
                ZoneId.of(store.getTimezone()));
        if (!scheduled.getEffectiveAt().isBefore(mandatoryAt)
                || !appliedAt.isBefore(mandatoryAt)) {
            throw new IllegalStateException(
                    "La licencia obliga VERI*FACTU; se cancela la salida programada");
        }
    }
}
