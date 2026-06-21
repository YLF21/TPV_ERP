package com.tpverp.backend.verifactu;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class VerifactuImmediateSubmissionListenerTest {

    @Test
    void submitsCommittedFiscalRecordImmediatelyWhenWorkerIsEnabled() {
        var worker = org.mockito.Mockito.mock(VerifactuSubmissionWorker.class);
        var listener = new VerifactuImmediateSubmissionListener(
                worker, new MockEnvironment().withProperty(
                        "tpv.verifactu.worker-enabled", "true"));
        var recordId = UUID.randomUUID();

        listener.submit(new FiscalRecordQueuedEvent(recordId));

        verify(worker).process(recordId);
    }

    @Test
    void ignoresImmediateEventWhenWorkerIsDisabled() {
        var worker = org.mockito.Mockito.mock(VerifactuSubmissionWorker.class);
        var listener = new VerifactuImmediateSubmissionListener(
                worker, new MockEnvironment().withProperty(
                        "tpv.verifactu.worker-enabled", "false"));

        listener.submit(new FiscalRecordQueuedEvent(UUID.randomUUID()));

        verify(worker, never()).process(org.mockito.ArgumentMatchers.any());
    }
}
