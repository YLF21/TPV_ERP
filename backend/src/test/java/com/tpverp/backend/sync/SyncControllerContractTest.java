package com.tpverp.backend.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class SyncControllerContractTest {

    @Test
    void exponeRecepcionDeEventosIdempotenteProtegida() throws NoSuchMethodException {
        assertThat(SyncController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/sync");
        var method = SyncController.class.getDeclaredMethod("receive", SyncInboundEventRequest.class);

        assertThat(method.getAnnotation(PostMapping.class).value())
                .containsExactly("/events");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("hasRole('ADMIN')");
    }

    @Test
    void exponeEstadoLocalDeOutboxProtegido() throws NoSuchMethodException {
        var method = SyncController.class.getDeclaredMethod("outboxStatus");

        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/outbox/status");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("hasRole('ADMIN')");
    }

    @Test
    void exponeEnvioManualDeOutboxProtegido() throws NoSuchMethodException {
        var method = SyncController.class.getDeclaredMethod("flushOutbox");

        assertThat(method.getAnnotation(PostMapping.class).value())
                .containsExactly("/outbox/flush");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("hasRole('ADMIN')");
    }

    @Test
    void flushResponseExponeEventosEnviados() {
        var response = new SyncOutboxFlushResponse(3);

        assertThat(response.sent()).isEqualTo(3);
    }

    @Test
    void requestMantieneIdentidadGlobalYPayload() {
        UUID eventId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();

        var request = new SyncInboundEventRequest(
                eventId, companyId, null, "DOCUMENTO", entityId,
                SyncOperation.CONFIRMAR, Map.of("total", "12.50"));

        assertThat(request.eventId()).isEqualTo(eventId);
        assertThat(request.companyId()).isEqualTo(companyId);
        assertThat(request.entityType()).isEqualTo("DOCUMENTO");
        assertThat(request.entityId()).isEqualTo(entityId);
        assertThat(request.payload()).containsEntry("total", "12.50");
    }
}
