package com.tpverp.backend.catalog;

import com.tpverp.backend.catalog.image.ProductImageProcessor;
import com.tpverp.backend.catalog.image.ProductImageStorage;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ProductImageService {

    public static final String CONTENT_TYPE = "image/webp";
    private static final Logger LOGGER = LoggerFactory.getLogger(ProductImageService.class);

    private final CatalogService catalogService;
    private final ProductImageProcessor processor;
    private final ProductImageStorage storage;

    public ProductImageService(
            CatalogService catalogService,
            ProductImageProcessor processor,
            ProductImageStorage storage) {
        this.catalogService = catalogService;
        this.processor = processor;
        this.storage = storage;
    }

    @Transactional
    public Product upload(UUID productId, byte[] content) {
        Product product = catalogService.product(productId);
        UUID previousImageId = product.getImageId();
        UUID imageId = UUID.randomUUID();
        var image = processor.process(content);
        write(product, imageId, image);
        try {
            product.attachImage(imageId, CONTENT_TYPE, image.image().length, image.sha256());
        } catch (RuntimeException exception) {
            deleteQuietly(product, imageId, "nueva tras error de metadatos");
            throw exception;
        }
        try {
            registerReplacementLifecycle(product, imageId, previousImageId);
        } catch (RuntimeException exception) {
            deleteQuietly(product, imageId, "nueva sin sincronizacion transaccional");
            throw exception;
        }
        return product;
    }
    // Processes the image, publishes it on disk, and stores only persistable metadata on the product.

    @Transactional(readOnly = true)
    public ProductImage read(UUID productId, boolean thumbnail) {
        Product product = catalogService.product(productId);
        UUID imageId = product.getImageId();
        if (imageId == null) {
            throw new IllegalArgumentException("El producto no tiene imagen");
        }
        try {
            return new ProductImage(storage.read(product.getStoreId(), product.getId(), imageId, thumbnail));
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo leer la imagen del producto", exception);
        }
    }
    // Recupera una variante interna WebP sin aceptar rutas desde la API.

    @Transactional
    public Product delete(UUID productId) {
        Product product = catalogService.product(productId);
        UUID imageId = product.getImageId();
        if (imageId != null) {
            product.clearImage();
            afterCommit(() -> deleteQuietly(product, imageId, "eliminada"));
        }
        return product;
    }

    public void validateUpload(byte[] content) {
        processor.validate(content);
    }

    private void write(Product product, UUID imageId, ProductImageProcessor.ProcessedImage image) {
        try {
            storage.write(product.getStoreId(), product.getId(), imageId, image.image(), image.thumbnail());
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo guardar la imagen del producto", exception);
        }
    }

    private void registerReplacementLifecycle(
            Product product, UUID newImageId, UUID previousImageId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            if (previousImageId != null) {
                deleteQuietly(product, previousImageId, "anterior");
            }
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (previousImageId != null) {
                    deleteQuietly(product, previousImageId, "anterior");
                }
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteQuietly(product, newImageId, "nueva tras rollback");
                }
            }
        });
    }

    private void deleteQuietly(Product product, UUID imageId, String description) {
        try {
            storage.delete(product.getStoreId(), product.getId(), imageId);
        } catch (IOException exception) {
            LOGGER.warn(
                    "No se pudo eliminar la imagen {} {} del producto {}",
                    description,
                    imageId,
                    product.getId(),
                    exception);
        }
    }

    private static void afterCommit(Runnable operation) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            operation.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                operation.run();
            }
        });
    }

    public record ProductImage(byte[] content) {}
}
