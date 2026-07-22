package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VerifactuSecretDeletionWorkerTest {

    @Test
    void deletesIdempotentlyAndCompletesClaimedJob() {
        var deletions = mock(VerifactuSecretDeletionService.class);
        var secrets = mock(VerifactuCertificateSecretStore.class);
        var jobId = UUID.randomUUID();
        when(deletions.claim(any(), eq(20))).thenReturn(List.of(
                new VerifactuSecretDeletionService.ClaimedSecretDeletion(
                        jobId, "company/certificate/private-key.dpapi")));
        when(deletions.complete(eq(jobId), any())).thenReturn(true);

        assertThat(new VerifactuSecretDeletionWorker(deletions, secrets).runOnce()).isOne();

        verify(secrets).delete("company/certificate/private-key.dpapi");
        verify(deletions).complete(eq(jobId), any());
        verify(deletions, never()).retry(any(), any());
    }

    @Test
    void schedulesRetryWhenPhysicalDeletionFails() {
        var deletions = mock(VerifactuSecretDeletionService.class);
        var secrets = mock(VerifactuCertificateSecretStore.class);
        var jobId = UUID.randomUUID();
        var path = "company/certificate/private-key.dpapi";
        when(deletions.claim(any(), eq(20))).thenReturn(List.of(
                new VerifactuSecretDeletionService.ClaimedSecretDeletion(jobId, path)));
        doThrow(new IllegalStateException("disk failure")).when(secrets).delete(path);

        assertThat(new VerifactuSecretDeletionWorker(deletions, secrets).runOnce()).isZero();

        verify(deletions).retry(eq(jobId), any());
        verify(deletions, never()).complete(any(), any());
    }
}
