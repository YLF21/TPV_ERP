package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalSubmissionLeaseTest {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    @Test
    void appliesOneFiveFifteenThirtyAndSixtyMinuteBackoff() {
        var state = new FiscalSubmissionState(
                UUID.randomUUID(), FiscalSubmissionStatus.PENDIENTE, NOW);
        var current = NOW;
        for (var expected : new int[] {1, 5, 15, 30, 60}) {
            state.claim(UUID.randomUUID(), UUID.randomUUID(), current, current.plusSeconds(120));
            state.markRetryableFailureWithClaim("NETWORK_ERROR", "timeout", current,
                    state.getClaimToken());
            assertThat(state.getNextAttemptAt()).isEqualTo(current.plusSeconds(expected * 60L));
            current = state.getNextAttemptAt();
        }
    }

    @Test
    void staleWorkerCannotCompleteAReclaimedLease() {
        var state = new FiscalSubmissionState(
                UUID.randomUUID(), FiscalSubmissionStatus.PENDIENTE, NOW);
        var first = UUID.randomUUID();
        state.claim(UUID.randomUUID(), first, NOW, NOW.plusSeconds(1));
        state.markRetryableFailureWithClaim("NETWORK_ERROR", "timeout", NOW,
                first);
        var second = UUID.randomUUID();
        state.claim(UUID.randomUUID(), second, NOW.plusSeconds(60),
                NOW.plusSeconds(180));

        assertThatThrownBy(() -> state.markWithClaim(
                FiscalSubmissionStatus.ACEPTADO, null, null,
                NOW.plusSeconds(61), first))
                .isInstanceOf(IllegalStateException.class);
        assertThat(state.getClaimToken()).isEqualTo(second);
    }

    @Test
    void expiredLeaseIsNotOwnedEvenBeforeAnotherWorkerReclaimsIt() {
        var state = new FiscalSubmissionState(
                UUID.randomUUID(), FiscalSubmissionStatus.PENDIENTE, NOW);
        var token = UUID.randomUUID();
        state.claim(UUID.randomUUID(), token, NOW, NOW.plusSeconds(1));

        assertThat(state.isOwnedBy(token, NOW.plusSeconds(1))).isFalse();
    }

}
