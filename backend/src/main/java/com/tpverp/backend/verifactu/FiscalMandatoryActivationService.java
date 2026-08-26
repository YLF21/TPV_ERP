package com.tpverp.backend.verifactu;

import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.StoreRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Applies the mandatory mode assigned by the active SaaS license. */
@Service
public class FiscalMandatoryActivationService {

    static final String CAUSE = "LICENSE_POLICY";

    private final LicenseRepository licenses;
    private final StoreRepository stores;
    private final VerifactuConfigurationRepository configurations;
    private final FiscalModeTransitionRepository transitions;
    private final FiscalEventService events;
    private final VerifactuActivationService activation;
    private final FiscalRuntimeProperties runtime;
    private final Clock clock;

    @Autowired
    public FiscalMandatoryActivationService(
            LicenseRepository licenses,
            StoreRepository stores,
            VerifactuConfigurationRepository configurations,
            FiscalModeTransitionRepository transitions,
            FiscalEventService events,
            VerifactuActivationService activation,
            FiscalRuntimeProperties runtime,
            Clock clock) {
        this.licenses = licenses;
        this.stores = stores;
        this.configurations = configurations;
        this.transitions = transitions;
        this.events = events;
        this.activation = activation;
        this.runtime = runtime;
        this.clock = clock;
    }

    /** Kept for focused tests and compatibility with existing embedders. */
    public FiscalMandatoryActivationService(
            LicenseRepository licenses,
            StoreRepository stores,
            VerifactuConfigurationRepository configurations,
            FiscalModeTransitionRepository transitions,
            FiscalEventService events,
            VerifactuActivationService activation,
            FiscalRuntimeProperties runtime) {
        this(licenses, stores, configurations, transitions, events, activation, runtime,
                Clock.systemUTC());
    }

    @Transactional
    public boolean activateIfDue(UUID licenseId, Instant checkedAt) {
        return apply(licenseId, checkedAt, false).activated();
    }

    /**
     * Locks the company fiscal configuration for the complete issuing
     * transaction and applies a due license transition before the record is
     * built. A failure is deliberately fatal: issuing as NO VERI*FACTU after
     * the mandatory instant is never an admissible fallback.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public VerifactuConfiguration prepareEmission(UUID licenseId, Instant checkedAt) {
        try {
            var result = apply(licenseId, checkedAt, true);
            if (result.configuration() == null) {
                throw new IllegalStateException("Configuracion fiscal no disponible");
            }
            return result.configuration();
        } catch (FiscalMandatoryActivationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new FiscalMandatoryActivationException(
                    "Emision fiscal bloqueada: no se pudo comprobar o aplicar la activacion "
                            + "automatica de licencia VERI*FACTU",
                    exception);
        }
    }

    private ActivationResult apply(
            UUID licenseId, Instant checkedAt, boolean emissionRequired) {
        Objects.requireNonNull(licenseId, "licenseId");
        Instant requestedAt = Objects.requireNonNull(checkedAt, "checkedAt");
        var license = licenses.findById(licenseId)
                .orElseThrow(() -> new IllegalStateException("Licencia fiscal no encontrada"));
        if (!license.isActiva()) {
            if (emissionRequired) {
                throw new FiscalMandatoryActivationException(
                        "Emision fiscal bloqueada: la licencia fiscal ya no esta activa");
            }
            return ActivationResult.notApplied();
        }
        if (license.getTaxpayerType() == null) {
            throw new IllegalStateException(
                    "La licencia activa no contiene el tipo de obligado fiscal");
        }
        // A missing activation date is a legacy/unconfigured commercial
        // policy, not a statutory order to switch to VERI*FACTU. Keep the
        // current PRE/NO mode usable and let an explicit policy activate it.
        if (license.getVerifactuActivationDate() == null) {
            if (!emissionRequired) {
                return ActivationResult.notApplied();
            }
            UUID companyId = stores.findWithCompanyById(license.getTiendaId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Tienda de la licencia no encontrada"))
                    .getEmpresa().getId();
            configurations.insertIfMissingWithMode(
                    UUID.randomUUID(), companyId, initialMode().name());
            var configuration = configurations.findForEmissionByCompanyId(companyId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Configuracion fiscal no encontrada"));
            return new ActivationResult(configuration, false);
        }
        var store = stores.findWithCompanyById(license.getTiendaId())
                .orElseThrow(() -> new IllegalStateException("Tienda de la licencia no encontrada"));
        UUID companyId = store.getEmpresa().getId();
        if (!companyId.equals(license.getLocalCompanyId())) {
            throw new IllegalStateException("La empresa de la licencia no coincide con su tienda");
        }
        Instant effectiveAt = activation.activationInstant(
                license.getTaxpayerType(),
                license.getVerifactuActivationDate(),
                ZoneId.of(store.getTimezone()));
        Instant observedAt = latest(requestedAt, clock.instant());
        if (!emissionRequired && observedAt.isBefore(effectiveAt)) {
            return ActivationResult.notApplied();
        }
        configurations.insertIfMissingWithMode(
                UUID.randomUUID(), companyId, initialMode().name());
        boolean dueBeforeLock = !observedAt.isBefore(effectiveAt);
        boolean alreadyVerifactu = configurations.findByCompanyId(companyId)
                .map(value -> value.getCurrentMode() == FiscalMode.VERIFACTU)
                .orElse(false);
        boolean requiresWriteLock = dueBeforeLock && !alreadyVerifactu;
        var configuration = (requiresWriteLock
                ? configurations.findForUpdateByCompanyId(companyId)
                : configurations.findForEmissionByCompanyId(companyId))
                .orElseThrow(() -> new IllegalStateException("Configuracion fiscal no encontrada"));
        // Re-read time only after acquiring the row lock. If waiting for another
        // issuer crosses the licensed automatic boundary, this issuer must not continue in NO.
        Instant now = latest(requestedAt, clock.instant());
        FiscalMode previous = configuration.getCurrentMode();
        if (previous == FiscalMode.VERIFACTU) {
            return new ActivationResult(configuration, false);
        }
        if (now.isBefore(effectiveAt)) {
            return new ActivationResult(configuration, false);
        }
        if (!requiresWriteLock) {
            throw new FiscalMandatoryActivationException(
                    "Emision fiscal bloqueada: la espera cruzo la fecha automatica de licencia "
                            + "VERI*FACTU; reintenta la operacion para aplicar la transicion");
        }
        long expectedVersion = configuration.getModeVersion();
        String reason = reason(license.getReferencia(),
                license.getVerifactuActivationDate(), license.getVerifactuPolicyVersion());
        if (previous == FiscalMode.NO_VERIFACTU) {
            events.create(companyId, license.getInstalacionId(), FiscalMode.NO_VERIFACTU,
                    FiscalEventType.END_NO_VERIFACTU, reason);
        }
        configuration.changeMode(FiscalMode.VERIFACTU, effectiveAt, null);
        configurations.save(configuration);
        transitions.save(new FiscalModeTransition(
                companyId,
                license.getInstalacionId(),
                previous,
                FiscalMode.VERIFACTU,
                now,
                effectiveAt,
                CAUSE,
                reason,
                expectedVersion));
        return new ActivationResult(configuration, true);
    }

    private FiscalMode initialMode() {
        return runtime.isSandbox() ? runtime.sandboxInitialMode() : FiscalMode.PRE_SIF;
    }

    private static Instant latest(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private static String reason(String reference, java.time.LocalDate activationDate,
            Long policyVersion) {
        return "Activacion automatica VERI*FACTU por licencia " + reference
                + "; fecha " + activationDate
                + "; politica " + (policyVersion == null ? "SIN_VERSION" : policyVersion);
    }

    private record ActivationResult(
            VerifactuConfiguration configuration, boolean activated) {

        private static ActivationResult notApplied() {
            return new ActivationResult(null, false);
        }
    }
}
