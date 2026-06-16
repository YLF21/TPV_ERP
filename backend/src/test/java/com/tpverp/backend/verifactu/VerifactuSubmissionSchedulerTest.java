package com.tpverp.backend.verifactu;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class VerifactuSubmissionSchedulerTest {

    @Test
    void noProcesaSiElWorkerAutomaticoEstaDesactivado() {
        var worker = org.mockito.Mockito.mock(VerifactuSubmissionWorker.class);
        var scheduler = new VerifactuSubmissionScheduler(
                worker,
                new MockEnvironment().withProperty("tpv.verifactu.worker-enabled", "false"));

        scheduler.tick();

        verify(worker, never()).processNext();
    }

    @Test
    void procesaUnRegistroSiEstaActivado() {
        var worker = org.mockito.Mockito.mock(VerifactuSubmissionWorker.class);
        var scheduler = new VerifactuSubmissionScheduler(
                worker,
                new MockEnvironment().withProperty("tpv.verifactu.worker-enabled", "true"));

        scheduler.tick();

        verify(worker).processNext();
    }
}
