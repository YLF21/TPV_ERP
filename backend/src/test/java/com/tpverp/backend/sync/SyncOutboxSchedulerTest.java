package com.tpverp.backend.sync;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class SyncOutboxSchedulerTest {

    private final SyncOutboxWorker worker = org.mockito.Mockito.mock(SyncOutboxWorker.class);
    private final Environment environment = org.mockito.Mockito.mock(Environment.class);
    private final SyncOutboxScheduler scheduler = new SyncOutboxScheduler(worker, environment);

    @Test
    void noEjecutaPorDefecto() {
        when(environment.getProperty("tpv.sync.worker-enabled", Boolean.class, false)).thenReturn(false);

        scheduler.tick();

        verify(worker, never()).runOnce();
    }

    @Test
    void ejecutaCuandoEstaActivado() {
        when(environment.getProperty("tpv.sync.worker-enabled", Boolean.class, false)).thenReturn(true);

        scheduler.tick();

        verify(worker).runOnce();
    }
}
