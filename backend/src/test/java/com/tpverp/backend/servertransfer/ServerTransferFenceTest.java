package com.tpverp.backend.servertransfer;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerTransferFenceTest {
    @TempDir Path keys;
    @Test void freezeDrainsInFlightWorkAndSurvivesRestart()throws Exception {
        var journal=new ServerTransferJournal(keys);var fence=new ServerTransferFence(journal);var id=UUID.randomUUID();
        assertThat(fence.enter()).isTrue();
        try(var pool=Executors.newSingleThreadExecutor()) {
            var frozen=pool.submit(()->fence.exclusive(()->journal.freeze(id)));
            assertThatThrownBy(()->frozen.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            fence.leave();frozen.get(5,TimeUnit.SECONDS);
        }
        assertThat(new ServerTransferFence(new ServerTransferJournal(keys)).enter()).isFalse();
        assertThatThrownBy(()->journal.releaseAfterCentralCancellation(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        assertThat(journal.frozen()).isTrue();journal.releaseAfterCentralCancellation(id);assertThat(fence.enter()).isTrue();fence.leave();
    }
    @Test void journalCannotBeReusedForAnotherTransfer(){var journal=new ServerTransferJournal(keys);journal.freeze(UUID.randomUUID());assertThatThrownBy(()->journal.freeze(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);}
}
