package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TicketCancellationOperationTest {

    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");

    @Test
    void uncertainCardResultCanBeRetriedWithTheSameRequest() {
        var operation = operation();
        operation.startCompensation(NOW);
        operation.reviewRequired("resultado incierto", NOW.plusSeconds(1));

        operation.startCompensation(NOW.plusSeconds(2));

        assertThat(operation.getStatus())
                .isEqualTo(TicketCancellationStatus.COMPENSATING);
    }

    @Test
    void requestIdCannotBeReusedForAnotherPayload() {
        var operation = operation();

        assertThatThrownBy(() -> operation.requireCompatible(
                UUID.randomUUID(), "another-hash"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("otra solicitud");
    }

    private static TicketCancellationOperation operation() {
        return new TicketCancellationOperation(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Error de cobro",
                "hash",
                Map.of(),
                NOW);
    }
}
