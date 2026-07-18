package com.tpverp.backend.catalog;

import com.tpverp.backend.organization.CurrentOrganization;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductBulkEditImageService {

    static final int MAX_IMAGES = 500;
    static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 100L * 1024 * 1024;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp");

    private final CurrentOrganization organization;
    private final ProductBulkEditRepository edits;
    private final ProductBulkEditImageRepository images;
    private final ProductRepository products;
    private final ProductImageService productImages;
    private final Clock clock;

    public ProductBulkEditImageService(
            CurrentOrganization organization,
            ProductBulkEditRepository edits,
            ProductBulkEditImageRepository images,
            ProductRepository products,
            ProductImageService productImages,
            Clock clock) {
        this.organization = organization;
        this.edits = edits;
        this.images = images;
        this.products = products;
        this.productImages = productImages;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ProductBulkEditImageView> list(UUID editId) {
        findEdit(editId, organization.currentStore().getId());
        return images.findViewsByEditId(editId);
    }

    @Transactional(readOnly = true)
    public ProductBulkEditImageContent read(UUID editId, UUID imageId) {
        findEdit(editId, organization.currentStore().getId());
        ProductBulkEditImage image = images.findByIdAndEdicion_Id(imageId, editId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Imagen de edicion masiva no encontrada"));
        return new ProductBulkEditImageContent(
                image.getFileName(), image.getContentType(), image.getContent());
    }

    @Transactional
    public ProductBulkEditImageSyncView sync(
            UUID editId,
            ProductBulkEditImageSyncRequest request,
            List<ProductBulkEditImageUpload> uploads,
            Authentication authentication) {
        UUID storeId = organization.currentStore().getId();
        ProductBulkEdit edit = findEdit(editId, storeId);
        requireVersion(edit, request.version());
        if (edit.getEstado() != ProductBulkEditStatus.PENDING) {
            throw new IllegalStateException(
                    "Las imagenes solo se pueden sincronizar en una version pendiente");
        }

        if (request.images() == null) {
            throw new IllegalArgumentException("images es obligatorio");
        }
        List<ProductBulkEditImageSyncItem> requested = request.images();
        List<ProductBulkEditImageUpload> received = uploads == null ? List.of() : List.copyOf(uploads);
        if (requested.size() > MAX_IMAGES) {
            throw new IllegalArgumentException(
                    "La lista de imagenes no puede superar " + MAX_IMAGES + " elementos");
        }
        if (received.size() > MAX_IMAGES) {
            throw new IllegalArgumentException(
                    "La carga no puede superar " + MAX_IMAGES + " archivos");
        }

        List<ProductBulkEditImage> current = images.findByEdicion_IdOrderByPosicionAsc(editId);
        Map<UUID, ProductBulkEditImage> currentById = new HashMap<>();
        current.forEach(image -> currentById.put(image.getId(), image));
        Set<UUID> retainedIds = new HashSet<>();
        Set<Integer> usedFiles = new HashSet<>();
        Map<Integer, ValidatedUpload> validatedFiles = new HashMap<>();
        Set<UUID> assignedProducts = new HashSet<>();
        List<ResolvedImage> resolved = new ArrayList<>(requested.size());
        long totalBytes = 0;

        for (int position = 0; position < requested.size(); position++) {
            ProductBulkEditImageSyncItem item = requested.get(position);
            if (item == null) {
                throw invalid(position, "el elemento no puede ser nulo");
            }
            ProductBulkEditImage existing = null;
            if (item.id() != null) {
                existing = currentById.get(item.id());
                if (existing == null) {
                    throw invalid(position, "id no pertenece al borrador: " + item.id());
                }
                if (!retainedIds.add(item.id())) {
                    throw invalid(position, "id duplicado: " + item.id());
                }
            }
            if (item.productId() != null && !assignedProducts.add(item.productId())) {
                throw invalid(position, "producto duplicado: " + item.productId());
            }

            ValidatedUpload upload = null;
            if (item.fileIndex() != null) {
                int fileIndex = item.fileIndex();
                if (fileIndex < 0 || fileIndex >= received.size()) {
                    throw invalid(position, "fileIndex fuera de rango: " + fileIndex);
                }
                usedFiles.add(fileIndex);
                int imagePosition = position;
                upload = validatedFiles.computeIfAbsent(
                        fileIndex,
                        ignored -> validateUpload(received.get(fileIndex), imagePosition));
            } else if (existing == null) {
                throw invalid(position, "una imagen nueva necesita fileIndex");
            }

            long resolvedSize = upload == null ? existing.getSize() : upload.internalContent().length;
            totalBytes += resolvedSize;
            if (totalBytes > MAX_TOTAL_BYTES) {
                throw new IllegalArgumentException(
                        "Las imagenes del borrador no pueden superar 100 MB");
            }
            resolved.add(new ResolvedImage(existing, item.productId(), position, upload));
        }
        if (usedFiles.size() != received.size()) {
            throw new IllegalArgumentException(
                    "Todos los archivos recibidos deben estar referenciados una sola vez");
        }
        validateProducts(storeId, assignedProducts);

        List<ProductBulkEditImage> synchronizedImages = new ArrayList<>(resolved.size());
        for (ResolvedImage value : resolved) {
            ProductBulkEditImage image = value.existing();
            if (image == null) {
                ValidatedUpload upload = value.upload();
                image = new ProductBulkEditImage(
                        edit,
                        value.productId(),
                        value.position(),
                        upload.fileName(),
                        upload.contentType(),
                        sha256(upload.internalContent()),
                        upload.internalContent());
            } else if (value.upload() == null) {
                image.reassign(value.productId(), value.position());
            } else {
                ValidatedUpload upload = value.upload();
                image.replace(
                        value.productId(),
                        value.position(),
                        upload.fileName(),
                        upload.contentType(),
                        sha256(upload.internalContent()),
                        upload.internalContent());
            }
            synchronizedImages.add(image);
        }

        List<ProductBulkEditImage> removed = current.stream()
                .filter(image -> !retainedIds.contains(image.getId()))
                .toList();
        images.deleteAll(removed);
        images.saveAll(synchronizedImages);
        edit.touch(organization.currentUser(authentication).getId(), clock.instant());
        try {
            edits.saveAndFlush(edit);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException exception) {
            throw staleVersion(editId, request.version(), null);
        }
        return new ProductBulkEditImageSyncView(
                edit.getVersion(),
                synchronizedImages.stream()
                        .sorted(Comparator.comparingInt(ProductBulkEditImage::getPosition))
                        .map(ProductBulkEditImageView::from)
                        .toList());
    }

    private ProductBulkEdit findEdit(UUID id, UUID storeId) {
        return edits.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Lista de edicion masiva no encontrada"));
    }

    private void validateProducts(UUID storeId, Set<UUID> productIds) {
        if (productIds.isEmpty()) {
            return;
        }
        Set<UUID> found = products.findAllByStoreIdAndIdIn(storeId, productIds).stream()
                .map(Product::getId)
                .collect(java.util.stream.Collectors.toSet());
        if (!found.equals(productIds)) {
            Set<UUID> missing = new HashSet<>(productIds);
            missing.removeAll(found);
            throw new IllegalArgumentException(
                    "Hay productos que no pertenecen a la tienda actual: " + missing);
        }
    }

    private ValidatedUpload validateUpload(
            ProductBulkEditImageUpload upload, int position) {
        if (upload == null || upload.internalContent() == null) {
            throw invalid(position, "el archivo es obligatorio");
        }
        byte[] content = upload.internalContent();
        if (content.length == 0 || content.length > MAX_FILE_BYTES) {
            throw invalid(position, "el archivo debe ocupar entre 1 byte y 5 MB");
        }
        String contentType = normalizedContentType(upload.contentType());
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw invalid(position, "tipo de imagen no permitido: " + upload.contentType());
        }
        String fileName = safeFileName(upload.fileName());
        productImages.validateUpload(content);
        return new ValidatedUpload(fileName, contentType, content);
    }

    private static String normalizedContentType(String value) {
        if (value == null) {
            return "";
        }
        int parameters = value.indexOf(';');
        return (parameters < 0 ? value : value.substring(0, parameters))
                .trim()
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static String safeFileName(String value) {
        String normalized = value == null ? "" : value.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        if (separator >= 0) {
            normalized = normalized.substring(separator + 1);
        }
        normalized = normalized.replaceAll("[\\p{Cntrl}]", "").trim();
        if (normalized.isEmpty()) {
            normalized = "imagen";
        }
        if (normalized.length() > 255) {
            throw new IllegalArgumentException("El nombre del archivo supera 255 caracteres");
        }
        return normalized;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("message.crypto.sha256_not_available", exception);
        }
    }

    private static void requireVersion(ProductBulkEdit edit, Long expectedVersion) {
        if (expectedVersion == null) {
            throw new IllegalArgumentException("version es obligatoria");
        }
        if (edit.getVersion() != expectedVersion) {
            throw staleVersion(edit.getId(), expectedVersion, edit.getVersion());
        }
    }

    private static IllegalStateException staleVersion(UUID id, long expected, Long actual) {
        String detail = actual == null ? "ya fue modificada" : "tiene version " + actual;
        return new IllegalStateException(
                "Conflicto de version en la lista " + id + ": se esperaba "
                        + expected + " y " + detail);
    }

    private static IllegalArgumentException invalid(int index, String detail) {
        return new IllegalArgumentException("images[" + index + "]: " + detail);
    }

    public record ProductBulkEditImageSyncRequest(
            @NotNull Long version,
            @NotNull @Size(max = MAX_IMAGES)
            List<@Valid ProductBulkEditImageSyncItem> images) {

        public ProductBulkEditImageSyncRequest {
            images = images == null ? null : List.copyOf(images);
        }
    }

    public record ProductBulkEditImageSyncItem(
            UUID id,
            UUID productId,
            @PositiveOrZero Integer fileIndex) {
    }

    public record ProductBulkEditImageUpload(
            String fileName,
            String contentType,
            byte[] content) {

        public ProductBulkEditImageUpload {
            content = content == null ? null : content.clone();
        }

        @Override
        public byte[] content() {
            return content == null ? null : content.clone();
        }

        byte[] internalContent() {
            return content;
        }
    }

    public record ProductBulkEditImageContent(
            String fileName,
            String contentType,
            byte[] content) {

        public ProductBulkEditImageContent {
            content = Objects.requireNonNull(content, "content").clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    private record ResolvedImage(
            ProductBulkEditImage existing,
            UUID productId,
            int position,
            ValidatedUpload upload) {
    }

    private record ValidatedUpload(
            String fileName,
            String contentType,
            byte[] internalContent) {
    }
}
