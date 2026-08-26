package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Publishes the local fiscal truth to SaaS without allowing SaaS to select a mode. */
@Service
public class FiscalStatusSyncPublisher {

    private static final String ENTITY_TYPE = "FISCAL_STATUS";
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofHours(12);

    private final CurrentOrganization organization;
    private final StoreRepository stores;
    private final InstallationRepository installations;
    private final LicenseRepository licenses;
    private final VerifactuConfigurationRepository configurations;
    private final FiscalRuntimeProperties runtime;
    private final SyncOutboxService outbox;
    private final Clock clock;

    public FiscalStatusSyncPublisher(CurrentOrganization organization, StoreRepository stores,
            InstallationRepository installations, LicenseRepository licenses,
            VerifactuConfigurationRepository configurations,
            FiscalRuntimeProperties runtime, SyncOutboxService outbox, Clock clock) {
        this.organization = organization;
        this.stores = stores;
        this.installations = installations;
        this.licenses = licenses;
        this.configurations = configurations;
        this.runtime = runtime;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public void publishCurrent() {
        var store = organization.currentStore();
        var company = organization.currentCompany();
        License license = licenses.findFirstByTienda_IdAndActivaTrueOrderByValidaDesdeDesc(store.getId())
                .orElse(null);
        var installationId = license == null
                ? FiscalInstallationResolver.resolveCurrent(organization, installations, licenses)
                        .getId()
                : license.getInstalacionId();
        publish(store, company.getId(), installationId, license);
    }

    private void publish(Store store, UUID companyId, UUID installationId, License license) {
        var configuration = configurations.findByCompanyId(companyId).orElse(null);
        Instant reportedAt = clock.instant();
        FiscalMode mode = configuration == null ? initialMode() : configuration.getCurrentMode();
        long modeVersion = configuration == null ? 0L : configuration.getModeVersion();
        LocalDate activationDate = license == null ? null : license.getVerifactuActivationDate();
        Long policyVersion = license == null ? null : license.getVerifactuPolicyVersion();
        String activationState = state(mode, activationDate, reportedAt, store.getTimezone());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("companyId", companyId.toString());
        payload.put("storeId", store.getId().toString());
        payload.put("installationId", installationId.toString());
        payload.put("effectiveMode", mode.name());
        payload.put("activationState", activationState);
        payload.put("modeVersion", modeVersion);
        payload.put("modeSince", configuration == null || configuration.getModeSince() == null
                ? null : configuration.getModeSince().toString());
        payload.put("activationDate", activationDate == null ? null : activationDate.toString());
        payload.put("policyVersion", policyVersion);
        payload.put("runtimeClass", runtime.runtimeClass().name());
        payload.put("endpointEnvironment", runtime.endpointEnvironment().name());
        payload.put("transportMode", runtime.transportMode().name());
        String statusFingerprint = fingerprint(payload);
        payload.put("statusFingerprint", statusFingerprint);
        var latest = outbox.latest(companyId, store.getId(), ENTITY_TYPE, companyId).orElse(null);
        if (latest != null
                && statusFingerprint.equals(latest.getPayload().get("statusFingerprint"))
                && latest.getCreatedAt().plus(HEARTBEAT_INTERVAL).isAfter(reportedAt)) {
            return;
        }
        payload.put("reportedAt", reportedAt.toString());
        outbox.enqueue(new SyncOutboundEventCommand(companyId, store.getId(), null,
                ENTITY_TYPE, companyId, SyncOperation.ACTUALIZAR, payload));
    }

    @Scheduled(fixedDelayString = "${tpv.sync.fiscal-status-delay-ms:300000}",
            initialDelayString = "${tpv.sync.fiscal-status-initial-delay-ms:15000}")
    public void publishScheduled() {
        List<License> activeLicenses;
        try {
            activeLicenses = licenses.findByActivaTrueOrderByValidaDesdeDesc();
        } catch (RuntimeException ignored) {
            return;
        }
        for (var license : activeLicenses) {
            if (license.getSaasCompanyId() == null || license.getSaasStoreId() == null) {
                continue;
            }
            try {
                var store = stores.findWithCompanyById(license.getTiendaId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Tienda de la licencia SaaS no encontrada"));
                publish(store, license.getLocalCompanyId(), license.getInstalacionId(), license);
            } catch (RuntimeException ignored) {
                // Each licensed store is isolated. Its outbox/status will be retried
                // without preventing APP VENTA or the remaining stores from working.
            }
        }
    }

    private FiscalMode initialMode() {
        return runtime.isSandbox() ? runtime.sandboxInitialMode() : FiscalMode.PRE_SIF;
    }

    private String state(FiscalMode mode, LocalDate activationDate, Instant now, String timezone) {
        if (activationDate == null) return "UNKNOWN";
        boolean due = !now.isBefore(activationDate.atStartOfDay(ZoneId.of(timezone)).toInstant());
        if (due && mode != FiscalMode.VERIFACTU) return "DUE_REVIEW";
        return mode == FiscalMode.PRE_SIF ? "PENDING" : "ACTIVE";
    }

    private String fingerprint(Map<String, Object> payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo resumir el estado fiscal", exception);
        }
    }
}
