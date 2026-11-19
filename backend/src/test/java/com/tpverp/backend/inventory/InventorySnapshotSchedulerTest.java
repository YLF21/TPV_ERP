package com.tpverp.backend.inventory;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class InventorySnapshotSchedulerTest {
    private final InventorySnapshotCapture capture = mock(InventorySnapshotCapture.class);
    private final MockEnvironment environment = new MockEnvironment()
            .withProperty("tpv.sync.central-url", "http://127.0.0.1");
    private final InventorySnapshotScheduler scheduler = new InventorySnapshotScheduler(capture, environment, 3600000);

    @Test
    void requiresSyncAndRespectsTheOptionalSwitch() {
        scheduler.tick();
        environment.setProperty("tpv.sync.worker-enabled", "true");
        environment.setProperty("tpv.sync.inventory-snapshot.enabled", "false");
        scheduler.tick();

        verifyNoInteractions(capture);
    }

    @Test
    void schedulesEachSiteWithTheConfiguredCadence() {
        environment.setProperty("tpv.sync.worker-enabled", "true");
        var site = new InventorySnapshotCapture.Site(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(capture.sites()).thenReturn(List.of(site));

        scheduler.tick();

        verify(capture).enqueue(site, Duration.ofHours(1));
    }
}
