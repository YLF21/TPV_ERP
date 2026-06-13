package com.tpverp.backend.catalog.image;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;

public class ProductImageStorage {

    private final Path root;
    private final AtomicFileMover mover;

    public ProductImageStorage(Path root) {
        this(root, (source, target) -> Files.move(
                source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }
    // Inicializa el almacenamiento dentro de una raiz normalizada.

    ProductImageStorage(Path root, AtomicFileMover mover) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.mover = Objects.requireNonNull(mover, "mover");
    }

    public void write(
            UUID storeId,
            UUID productId,
            UUID imageId,
            byte[] image,
            byte[] thumbnail) throws IOException {
        Path directory = productDirectory(storeId, productId);
        Files.createDirectories(directory);
        Path imageTarget = imagePath(directory, imageId, false);
        Path thumbnailTarget = imagePath(directory, imageId, true);
        Path imageTemporary = Files.createTempFile(directory, ".image-", ".tmp");
        Path thumbnailTemporary = Files.createTempFile(directory, ".thumbnail-", ".tmp");
        try {
            Files.write(imageTemporary, Objects.requireNonNull(image, "image"));
            Files.write(thumbnailTemporary, Objects.requireNonNull(thumbnail, "thumbnail"));
            mover.move(imageTemporary, imageTarget);
            mover.move(thumbnailTemporary, thumbnailTarget);
        } catch (IOException exception) {
            Files.deleteIfExists(imageTarget);
            Files.deleteIfExists(thumbnailTarget);
            throw exception;
        } finally {
            Files.deleteIfExists(imageTemporary);
            Files.deleteIfExists(thumbnailTemporary);
        }
    }
    // Publica las dos variantes o elimina cualquier archivo nuevo si la operacion queda incompleta.

    public byte[] read(UUID storeId, UUID productId, UUID imageId, boolean thumbnail) throws IOException {
        return Files.readAllBytes(imagePath(productDirectory(storeId, productId), imageId, thumbnail));
    }
    // Lee una variante usando identificadores internos, nunca rutas proporcionadas por el cliente.

    public boolean exists(UUID storeId, UUID productId, UUID imageId) {
        Path directory = productDirectory(storeId, productId);
        return Files.isRegularFile(imagePath(directory, imageId, false))
                && Files.isRegularFile(imagePath(directory, imageId, true));
    }

    public void delete(UUID storeId, UUID productId, UUID imageId) throws IOException {
        Path directory = productDirectory(storeId, productId);
        Files.deleteIfExists(imagePath(directory, imageId, false));
        Files.deleteIfExists(imagePath(directory, imageId, true));
        deleteIfEmpty(directory);
    }
    // Elimina ambas variantes y retira el directorio del producto cuando queda vacio.

    public void deleteProductDirectory(UUID storeId, UUID productId) throws IOException {
        Path directory = productDirectory(storeId, productId);
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public Path root() {
        return root;
    }

    private Path productDirectory(UUID storeId, UUID productId) {
        Path resolved = root.resolve(storeId.toString()).resolve(productId.toString()).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalStateException("Ruta de imagen fuera del almacenamiento");
        }
        return resolved;
    }

    private Path imagePath(Path directory, UUID imageId, boolean thumbnail) {
        return directory.resolve(imageId + (thumbnail ? "-thumb.webp" : ".webp"));
    }

    private void deleteIfEmpty(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var children = Files.list(directory)) {
            if (children.findAny().isEmpty()) {
                Files.deleteIfExists(directory);
            }
        }
    }

    @FunctionalInterface
    interface AtomicFileMover {
        void move(Path source, Path target) throws IOException;
    }
}
