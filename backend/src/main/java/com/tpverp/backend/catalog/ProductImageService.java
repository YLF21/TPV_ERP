package com.tpverp.backend.catalog;

import com.tpverp.backend.catalog.image.ProductImageProcessor;
import com.tpverp.backend.catalog.image.ProductImageStorage;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductImageService {

    public static final String CONTENT_TYPE = "image/webp";

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
        product.attachImage(imageId, CONTENT_TYPE, image.image().length, image.sha256());
        deletePrevious(product, previousImageId);
        return product;
    }
    // Procesa la imagen, la publica en disco y deja en producto solo metadatos persistibles.

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
            deleteStored(product, imageId);
            product.clearImage();
        }
        return product;
    }

    private void write(Product product, UUID imageId, ProductImageProcessor.ProcessedImage image) {
        try {
            storage.write(product.getStoreId(), product.getId(), imageId, image.image(), image.thumbnail());
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo guardar la imagen del producto", exception);
        }
    }

    private void deletePrevious(Product product, UUID previousImageId) {
        if (previousImageId != null) {
            deleteStored(product, previousImageId);
        }
    }

    private void deleteStored(Product product, UUID imageId) {
        try {
            storage.delete(product.getStoreId(), product.getId(), imageId);
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo eliminar la imagen anterior del producto", exception);
        }
    }

    public record ProductImage(byte[] content) {}
}
