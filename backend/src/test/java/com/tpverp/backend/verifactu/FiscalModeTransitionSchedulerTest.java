package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class FiscalModeTransitionSchedulerTest {

    @Test
    void aplicaUnaTransicionVencidaYGeneraEvento01() {
        var companyId = UUID.randomUUID();
        var installationId = UUID.randomUUID();
        var transitions = mock(FiscalModeTransitionRepository.class);
        var configurations = mock(VerifactuConfigurationRepository.class);
        var events = mock(FiscalEventService.class);
        var runtime = new FiscalRuntimeProperties(new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "REAL")
                .withProperty("tpv.verifactu.endpoint-environment", "TEST")
                .withProperty("tpv.verifactu.transport-mode", "AEAT"));
        var configuration = new VerifactuConfiguration(companyId);
        configuration.changeMode(FiscalMode.VERIFACTU, Instant.parse("2027-01-01T00:00:00Z"), null);
        var scheduled = new FiscalModeTransition(companyId, installationId,
                FiscalMode.VERIFACTU, FiscalMode.NO_VERIFACTU,
                Instant.parse("2027-01-02T00:00:00Z"),
                Instant.parse("2027-02-01T00:00:00Z"), "ADMIN", "fin", 1,
                LocalDate.of(2027, 1, 31), "ACK-1");
        when(transitions.findDueWithoutAppliedTransition(
                FiscalModeTransitionStatus.PROGRAMADA, FiscalModeTransitionStatus.APLICADA,
                Instant.parse("2027-02-02T00:00:00Z"))).thenReturn(List.of(scheduled));
        when(configurations.findForUpdateByCompanyId(companyId)).thenReturn(Optional.of(configuration));

        var scheduler = new FiscalModeTransitionScheduler(transitions, configurations, events, runtime);

        assertThat(scheduler.applyDue(Instant.parse("2027-02-02T00:00:00Z"))).isEqualTo(1);
        assertThat(configuration.getCurrentMode()).isEqualTo(FiscalMode.NO_VERIFACTU);
        verify(events).create(eq(companyId), eq(installationId), eq(FiscalMode.NO_VERIFACTU),
                eq(FiscalEventType.START_NO_VERIFACTU), eq("Salida VERI*FACTU; ACK ACK-1"));
    }
}
