package com.tpverp.backend.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "producto_edicion_masiva_imagen")
public class ProductBulkEditImage {

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "edicion_id", nullable = false)
    private ProductBulkEdit edicion;
    @Column(name = "producto_id")
    private UUID productId;
    @Column(nullable = false)
    private int posicion;
    @Column(name = "nombre_archivo", nullable = false, length = 255)
    private String fileName;
    @Column(name = "tipo_contenido", nullable = false, length = 100)
    private String contentType;
    @Column(name = "tamano", nullable = false)
    private long size;
    @Column(nullable = false, length = 64)
    private String sha256;
    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] contenido;

    protected ProductBulkEditImage() {
    }

    public ProductBulkEditImage(
            ProductBulkEdit edit,
            UUID productId,
            int position,
            String fileName,
            String contentType,
            String sha256,
            byte[] content) {
        id = UUID.randomUUID();
        edicion = Objects.requireNonNull(edit, "edit");
        replace(productId, position, fileName, contentType, sha256, content);
    }

    public void reassign(UUID productId, int position) {
        requirePosition(position);
        this.productId = productId;
        posicion = position;
    }

    public void replace(
            UUID productId,
            int position,
            String fileName,
            String contentType,
            String sha256,
            byte[] content) {
        reassign(productId, position);
        this.fileName = required(fileName, "fileName", 255);
        this.contentType = required(contentType, "contentType", 100);
        this.sha256 = required(sha256, "sha256", 64);
        contenido = Arrays.copyOf(Objects.requireNonNull(content, "content"), content.length);
        size = contenido.length;
    }

    public UUID getId() { return id; }
    public ProductBulkEdit getEdicion() { return edicion; }
    public UUID getProductId() { return productId; }
    public int getPosition() { return posicion; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public long getSize() { return size; }
    public String getSha256() { return sha256; }
    public byte[] getContent() { return Arrays.copyOf(contenido, contenido.length); }

    private static void requirePosition(int position) {
        if (position < 0) {
            throw new IllegalArgumentException("position no puede ser negativo");
        }
    }

    private static String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " supera " + maxLength + " caracteres");
        }
        return normalized;
    }
}
