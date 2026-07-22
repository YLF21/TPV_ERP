package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VerifactuSecretDeletionJobTest {

    private static final Instant NOW = Instant.parse("2026-07-22T10:00:00Z");

    @Test
    void expiredLeaseCanBeClaimedByAnotherWorker() {
        var job = job();
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        job.claim(first, NOW, NOW.plusSeconds(30));

        job.claim(second, NOW.plusSeconds(31), NOW.plusSeconds(61));

        assertThat(job.getProcessingOwner()).isEqualTo(second);
        assertThat(job.getAttempts()).isEqualTo(2);
        assertThatThrownBy(() -> job.complete(first, NOW.plusSeconds(32)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failureReturnsJobToPendingAndCompletionIsFinal() {
        var job = job();
        var owner = UUID.randomUUID();
        job.claim(owner, NOW, NOW.plusSeconds(30));
        job.retry(owner, NOW.plusSeconds(60), "SECRET_DELETE_FAILED");

        assertThat(job.getStatus()).isEqualTo(VerifactuSecretDeletionStatus.PENDIENTE);
        assertThat(job.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(job.getLastError()).isEqualTo("SECRET_DELETE_FAILED");

        job.claim(owner, NOW.plusSeconds(60), NOW.plusSeconds(90));
        job.complete(owner, NOW.plusSeconds(61));
        assertThat(job.getStatus()).isEqualTo(VerifactuSecretDeletionStatus.COMPLETADO);
        assertThat(job.getCompletedAt()).isEqualTo(NOW.plusSeconds(61));
    }

    private static VerifactuSecretDeletionJob job() {
        return VerifactuSecretDeletionJob.pending(
                UUID.randomUUID(), UUID.randomUUID(),
                "company/certificate/private-key.dpapi",
                VerifactuSecretDeletionReason.RETENTION_PURGE, NOW);
    }
}
