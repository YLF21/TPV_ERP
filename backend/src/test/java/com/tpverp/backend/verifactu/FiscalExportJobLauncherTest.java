package com.tpverp.backend.verifactu;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class FiscalExportJobLauncherTest {
    @Test
    void temporaryExecutorBackpressureReleasesClaimForNextTick() {
        var service = mock(FiscalExportJobService.class);
        var executor = mock(ThreadPoolTaskExecutor.class);
        var id = UUID.randomUUID();
        doThrow(new RejectedExecutionException("full")).when(executor).execute(any(Runnable.class));

        new FiscalExportJobLauncher(service, executor).launch(id);

        verify(service, org.mockito.Mockito.never()).run(id);
    }
}
