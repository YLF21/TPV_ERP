package com.tpverp.backend.backup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.backend.catalog.image.ProductImageStorage;
import com.tpverp.backend.document.template.DocumentTemplateArtifactStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SharedFileSnapshotGuardTest {

    @TempDir Path directory;

    @Test
    void existingImagesAndTemplatesCannotBeDeletedUntilSnapshotCloses() throws Exception {
        var images = new ProductImageStorage(directory.resolve("images"));
        var templates = new DocumentTemplateArtifactStorage(directory.resolve("templates"));
        var store = UUID.randomUUID();
        var product = UUID.randomUUID();
        var image = UUID.randomUUID();
        images.write(store, product, image, new byte[] {1}, new byte[] {2});
        var artifact = templates.write(UUID.randomUUID(), new byte[] {3}, new byte[] {4});
        try (var executor = Executors.newFixedThreadPool(2)) {
            var entered = new CountDownLatch(2);
            var snapshot = SharedFileSnapshotGuard.snapshot();
            try {
                var imageDeletion = executor.submit(() -> {
                    entered.countDown();
                    images.delete(store, product, image);
                    return true;
                });
                var templateDeletion = executor.submit(() -> {
                    entered.countDown();
                    templates.delete(artifact.reference());
                    return true;
                });
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> imageDeletion.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                assertThatThrownBy(() -> templateDeletion.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                assertThat(images.read(store, product, image, false)).containsExactly((byte) 1);
                assertThat(templates.readSource(artifact.reference())).containsExactly((byte) 3);
                snapshot.close();
                assertThat(imageDeletion.get(5, TimeUnit.SECONDS)).isTrue();
                assertThat(templateDeletion.get(5, TimeUnit.SECONDS)).isTrue();
                assertThat(images.exists(store, product, image)).isFalse();
                assertThat(Files.exists(templates.root().resolve(artifact.reference()))).isFalse();
            } finally {
                snapshot.close();
            }
        }
    }

    @Test
    void snapshotWaitsForTransactionCompletionAfterMutationMethodReturns() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                try (var mutation = SharedFileSnapshotGuard.transactionalMutation()) {
                    // The method scope ends before its database commit.
                }
                var entered = new CountDownLatch(1);
                var capture = executor.submit(() -> {
                    entered.countDown();
                    try (var snapshot = SharedFileSnapshotGuard.snapshot()) {
                        return true;
                    }
                });
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> capture.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                TransactionSynchronizationManager.getSynchronizations().forEach(
                        synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
                assertThat(capture.get(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                TransactionSynchronizationManager.getSynchronizations().forEach(
                        synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
                TransactionSynchronizationManager.clearSynchronization();
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        }
    }
}
