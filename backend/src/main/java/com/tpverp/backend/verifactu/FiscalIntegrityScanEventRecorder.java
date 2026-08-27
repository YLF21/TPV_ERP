package com.tpverp.backend.verifactu;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commits scan-start evidence before the potentially long integrity read. */
@Service
public class FiscalIntegrityScanEventRecorder {

    private final FiscalEventService events;
    private final VerifactuConfigurationRepository configurations;
    private final FiscalAlarmRepository alarms;

    public FiscalIntegrityScanEventRecorder(FiscalEventService events,
            VerifactuConfigurationRepository configurations,
            FiscalAlarmRepository alarms) {
        this.events = events;
        this.configurations = configurations;
        this.alarms = alarms;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordStarted(UUID companyId, UUID installationId, FiscalMode mode) {
        requireCurrentMode(companyId, mode);
        events.create(companyId, installationId, mode,
                FiscalEventType.BILLING_ANOMALY_SCAN_STARTED, null);
        events.create(companyId, installationId, mode,
                FiscalEventType.EVENT_ANOMALY_SCAN_STARTED, null);
    }

    /** Serializes final evidence with every fiscal mode transition. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordResult(UUID companyId, UUID installationId, FiscalMode mode,
            String alarmDetail, String billingDetail, String eventDetail) {
        requireCurrentMode(companyId, mode);
        if (mode != FiscalMode.NO_VERIFACTU || alarmDetail == null) {
            return;
        }
        alarms.save(new FiscalAlarm(companyId, installationId,
                "INTEGRIDAD_ANOMALIAS", alarmDetail, java.time.Instant.now()));
        if (billingDetail != null) {
            events.create(companyId, installationId, mode,
                    FiscalEventType.BILLING_ANOMALY_DETECTED, billingDetail);
        }
        if (eventDetail != null) {
            events.create(companyId, installationId, mode,
                    FiscalEventType.EVENT_ANOMALY_DETECTED, eventDetail);
        }
    }

    private void requireCurrentMode(UUID companyId, FiscalMode expected) {
        var current = configurations.findForUpdateByCompanyId(companyId)
                .map(VerifactuConfiguration::getCurrentMode)
                .orElse(FiscalMode.PRE_SIF);
        if (current != expected) {
            throw new IllegalStateException("fiscal_integrity_mode_changed");
        }
    }
}
