package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalIntegrityScanEventRecorderTest {

    @Test
    void finalEvidenceIsRejectedAfterModeTransition() {
        var companyId = UUID.randomUUID();
        var installationId = UUID.randomUUID();
        var configurations = mock(VerifactuConfigurationRepository.class);
        var configuration = new VerifactuConfiguration(companyId);
        configuration.changeMode(FiscalMode.VERIFACTU, Instant.now(), null);
        when(configurations.findForUpdateByCompanyId(companyId))
                .thenReturn(Optional.of(configuration));
        var events = mock(FiscalEventService.class);
        var alarms = mock(FiscalAlarmRepository.class);
        var recorder = new FiscalIntegrityScanEventRecorder(events, configurations, alarms);

        assertThatThrownBy(() -> recorder.recordResult(companyId, installationId,
                FiscalMode.NO_VERIFACTU, "alarm", "billing", "events"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("fiscal_integrity_mode_changed");

        verify(alarms, never()).save(any());
        verify(events, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void finalEvidenceIsWrittenUnderTheLockedNoVerifactuMode() {
        var companyId = UUID.randomUUID();
        var installationId = UUID.randomUUID();
        var configurations = mock(VerifactuConfigurationRepository.class);
        var configuration = new VerifactuConfiguration(companyId);
        configuration.changeMode(FiscalMode.NO_VERIFACTU, Instant.now(), null);
        when(configurations.findForUpdateByCompanyId(companyId))
                .thenReturn(Optional.of(configuration));
        var events = mock(FiscalEventService.class);
        var alarms = mock(FiscalAlarmRepository.class);
        var recorder = new FiscalIntegrityScanEventRecorder(events, configurations, alarms);

        recorder.recordResult(companyId, installationId, FiscalMode.NO_VERIFACTU,
                "alarm", "billing", "events");

        verify(alarms).save(any(FiscalAlarm.class));
        verify(events).create(companyId, installationId, FiscalMode.NO_VERIFACTU,
                FiscalEventType.BILLING_ANOMALY_DETECTED, "billing");
        verify(events).create(companyId, installationId, FiscalMode.NO_VERIFACTU,
                FiscalEventType.EVENT_ANOMALY_DETECTED, "events");
    }
}
