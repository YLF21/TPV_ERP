package com.tpverp.backend.verifactu;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doAnswer;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class FiscalIntegrityJobLauncherTest {

    @Test
    void cancellationPreventsAQueuedLocalWorkerFromStarting() {
        var service = mock(FiscalIntegrityJobService.class);
        var executor = mock(ThreadPoolTaskExecutor.class);
        var task = new AtomicReference<Runnable>();
        doAnswer(invocation -> {
            task.set(invocation.getArgument(0, Runnable.class));
            return null;
        }).when(executor).execute(any(Runnable.class));
        var launcher = new FiscalIntegrityJobLauncher(service, executor);
        var id = UUID.randomUUID();

        launcher.launch(id);
        launcher.cancel(id);
        task.get().run();

        verify(service, never()).run(id);
    }
}
