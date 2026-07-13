package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.image.ProductImageProcessor;
import com.tpverp.backend.catalog.image.ProductImageStorage;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class ProductImageServiceTest {

    @Mock private CatalogService catalogService;
    @Mock private ProductImageProcessor processor;
    @Mock private ProductImageStorage storage;

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void uploadStoresWebpVariantsAndUpdatesMetadata() throws Exception {
        Product product = product();
        byte[] original = {9, 8, 7};
        byte[] webp = {1, 2};
        byte[] thumbnail = {3};
        when(catalogService.product(product.getId())).thenReturn(product);
        when(processor.process(original))
                .thenReturn(new ProductImageProcessor.ProcessedImage(webp, thumbnail, 2, 1, "abcd"));

        Product updated = new ProductImageService(catalogService, processor, storage)
                .upload(product.getId(), original);

        ArgumentCaptor<UUID> imageId = ArgumentCaptor.forClass(UUID.class);
        verify(storage).write(
                eq(product.getStoreId()), eq(product.getId()), imageId.capture(), eq(webp), eq(thumbnail));
        assertThat(updated.getImageId()).isEqualTo(imageId.getValue());
        assertThat(updated.getImageType()).isEqualTo(ProductImageService.CONTENT_TYPE);
        assertThat(updated.getImageSize()).isEqualTo(2L);
        assertThat(updated.getImageHash()).isEqualTo("ABCD");
    }

    @Test
    void readUsesProductStoreAndCurrentImageId() throws Exception {
        Product product = product();
        UUID imageId = UUID.randomUUID();
        product.attachImage(imageId, ProductImageService.CONTENT_TYPE, 2, "abcd");
        when(catalogService.product(product.getId())).thenReturn(product);
        when(storage.read(product.getStoreId(), product.getId(), imageId, true)).thenReturn(new byte[] {4});

        var image = new ProductImageService(catalogService, processor, storage)
                .read(product.getId(), true);

        assertThat(image.content()).containsExactly(4);
    }

    @Test
    void rollbackDeletesTheNewImageAndKeepsThePreviousImage() throws Exception {
        Product product = product();
        UUID previousImageId = UUID.randomUUID();
        product.attachImage(previousImageId, ProductImageService.CONTENT_TYPE, 2, "old");
        byte[] original = {9};
        when(catalogService.product(product.getId())).thenReturn(product);
        when(processor.process(original)).thenReturn(
                new ProductImageProcessor.ProcessedImage(
                        new byte[] {1}, new byte[] {2}, 1, 1, "new"));
        TransactionSynchronizationManager.initSynchronization();

        new ProductImageService(catalogService, processor, storage)
                .upload(product.getId(), original);

        ArgumentCaptor<UUID> newImageId = ArgumentCaptor.forClass(UUID.class);
        verify(storage).write(
                eq(product.getStoreId()),
                eq(product.getId()),
                newImageId.capture(),
                eq(new byte[] {1}),
                eq(new byte[] {2}));
        verify(storage, never()).delete(product.getStoreId(), product.getId(), previousImageId);

        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(
                        TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(storage).delete(product.getStoreId(), product.getId(), newImageId.getValue());
        verify(storage, never()).delete(product.getStoreId(), product.getId(), previousImageId);
    }

    @Test
    void commitDeletesThePreviousImageAndKeepsTheNewImage() throws Exception {
        Product product = product();
        UUID previousImageId = UUID.randomUUID();
        product.attachImage(previousImageId, ProductImageService.CONTENT_TYPE, 2, "old");
        byte[] original = {9};
        when(catalogService.product(product.getId())).thenReturn(product);
        when(processor.process(original)).thenReturn(
                new ProductImageProcessor.ProcessedImage(
                        new byte[] {1}, new byte[] {2}, 1, 1, "new"));
        TransactionSynchronizationManager.initSynchronization();

        new ProductImageService(catalogService, processor, storage)
                .upload(product.getId(), original);

        ArgumentCaptor<UUID> newImageId = ArgumentCaptor.forClass(UUID.class);
        verify(storage).write(
                eq(product.getStoreId()),
                eq(product.getId()),
                newImageId.capture(),
                eq(new byte[] {1}),
                eq(new byte[] {2}));
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }

        verify(storage).delete(product.getStoreId(), product.getId(), previousImageId);
        verify(storage, never()).delete(product.getStoreId(), product.getId(), newImageId.getValue());
    }

    @Test
    void deletingAnImageWaitsForCommitBeforeRemovingTheFile() throws Exception {
        Product product = product();
        UUID imageId = UUID.randomUUID();
        product.attachImage(imageId, ProductImageService.CONTENT_TYPE, 2, "old");
        when(catalogService.product(product.getId())).thenReturn(product);
        TransactionSynchronizationManager.initSynchronization();

        new ProductImageService(catalogService, processor, storage).delete(product.getId());

        assertThat(product.getImageId()).isNull();
        verify(storage, never()).delete(product.getStoreId(), product.getId(), imageId);
        TransactionSynchronizationManager.getSynchronizations().forEach(
                TransactionSynchronization::afterCommit);
        verify(storage).delete(product.getStoreId(), product.getId(), imageId);
    }

    private Product product() {
        return new Product(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                "Producto",
                null,
                BigDecimal.ZERO,
                true);
    }
}
