package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class VerifactuClockMonitorTest {

    @Test
    void compruebaHoraAlArrancarYDevuelveUltimoEstado() {
        var service = Mockito.mock(VerifactuClockService.class);
        var status = status(false);
        when(service.check()).thenReturn(status);
        var monitor = new VerifactuClockMonitor(service);

        monitor.checkOnStartup();

        assertThat(monitor.current()).isSameAs(status);
        verify(service).check();
    }

    @Test
    void siNoHayEstadoPrevioLoCalculaAlConsultar() {
        var service = Mockito.mock(VerifactuClockService.class);
        when(service.check()).thenReturn(status(true));
        var monitor = new VerifactuClockMonitor(service);

        assertThat(monitor.current().warning()).isTrue();

        verify(service).check();
    }

    @Test
    void laRevisionDiariaActualizaElEstado() {
        var service = Mockito.mock(VerifactuClockService.class);
        when(service.check()).thenReturn(status(false), status(true));
        var monitor = new VerifactuClockMonitor(service);

        monitor.checkOnStartup();
        monitor.checkDaily();

        assertThat(monitor.current().warning()).isTrue();
        verify(service, times(2)).check();
    }

    private static VerifactuClockStatusView status(boolean warning) {
        return new VerifactuClockStatusView(
                warning,
                warning ? "CLOCK_DRIFT_OVER_5_MINUTES" : null,
                Instant.EPOCH,
                Instant.EPOCH,
                0,
                300,
                Instant.EPOCH);
    }
}
