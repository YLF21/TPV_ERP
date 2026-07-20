package com.tpverp.bridge.app;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeInstanceLockTest {
    @TempDir Path temporary;

    @Test
    void allowsOnlyOneBridgeProcessPerJournalDirectory() throws Exception {
        try (var first = BridgeInstanceLock.acquire(temporary)) {
            assertThatThrownBy(() -> BridgeInstanceLock.acquire(temporary))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Another");
        }
        try (var afterRestart = BridgeInstanceLock.acquire(temporary)) {
            // The operating-system lock is reusable after a clean shutdown.
        }
    }
}
