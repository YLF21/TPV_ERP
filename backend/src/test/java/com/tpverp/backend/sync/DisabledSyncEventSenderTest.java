package com.tpverp.backend.sync;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DisabledSyncEventSenderTest {

    @Test
    void noMarcaEventosComoEnviadosSinTransporteConfigurado() {
        var sender = new DisabledSyncEventSender();
        var event = new SyncOutboxEvent(
                UUID.randomUUID(), null, null, "DOCUMENTO", UUID.randomUUID(),
                SyncOperation.CONFIRMAR, Map.of(), Instant.parse("2026-06-30T12:00:00Z"));

        assertThatThrownBy(() -> sender.send(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tpv.sync.central-url");
    }
}
