package com.tpverp.backend.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SyncOutboxWorkerTest {

    private static final Instant NOW = Instant.parse("2026-06-30T12:30:00Z");

    @Mock
    private SyncOutboxService outbox;
    @Mock
    private SyncEventSender sender;

    @Test
    void enviaPendientesYMarcaEnviado() {
        var event = event();
        var claimToken = UUID.randomUUID();
        event.claim(claimToken, NOW);
        when(outbox.claimBatch(100, Duration.ofMinutes(2))).thenReturn(List.of(event));
        when(outbox.markSent(event.getEventId(), claimToken))
                .thenAnswer(ignored -> event.markSent(claimToken, NOW));
        var worker = worker();

        int sent = worker.runOnce();

        assertThat(sent).isOne();
        assertThat(event.getStatus()).isEqualTo(SyncOutboxStatus.ENVIADO);
        assertThat(event.getSentAt()).isEqualTo(NOW);
        assertThat(event.getAttempts()).isOne();
        InOrder order = Mockito.inOrder(sender, outbox);
        order.verify(sender).send(event);
        order.verify(outbox).markSent(event.getEventId(), claimToken);
    }

    @Test
    void marcaErrorYContinuaConElSiguienteEvento() {
        var first = event();
        var second = event();
        var firstClaimToken = UUID.randomUUID();
        var secondClaimToken = UUID.randomUUID();
        first.claim(firstClaimToken, NOW);
        second.claim(secondClaimToken, NOW);
        when(outbox.claimBatch(100, Duration.ofMinutes(2))).thenReturn(List.of(first, second));
        when(outbox.markFailed(
                first.getEventId(), firstClaimToken, "saas caido", 10,
                Duration.ofSeconds(5), Duration.ofMinutes(15)))
                .thenAnswer(ignored -> first.markRetry(
                        firstClaimToken, "saas caido", NOW.plusSeconds(5), NOW));
        when(outbox.markSent(second.getEventId(), secondClaimToken))
                .thenAnswer(ignored -> second.markSent(secondClaimToken, NOW));
        doThrow(new IllegalStateException("saas caido")).when(sender).send(first);
        var worker = worker();

        int sent = worker.runOnce();

        assertThat(sent).isOne();
        assertThat(first.getStatus()).isEqualTo(SyncOutboxStatus.ERROR);
        assertThat(first.getLastError()).isEqualTo("saas caido");
        assertThat(first.getAttempts()).isOne();
        assertThat(second.getStatus()).isEqualTo(SyncOutboxStatus.ENVIADO);
        verify(outbox).markFailed(
                first.getEventId(), firstClaimToken, "saas caido", 10,
                Duration.ofSeconds(5), Duration.ofMinutes(15));
        verify(outbox).markSent(second.getEventId(), secondClaimToken);
    }

    private SyncOutboxWorker worker() {
        return new SyncOutboxWorker(outbox, sender, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SyncOutboxEvent event() {
        return new SyncOutboxEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "DOCUMENTO",
                UUID.randomUUID(),
                SyncOperation.CONFIRMAR,
                Map.of("numero", "T-1"),
                NOW.minusSeconds(60));
    }
}
