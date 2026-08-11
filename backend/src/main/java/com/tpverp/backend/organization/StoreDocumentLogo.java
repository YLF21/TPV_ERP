package com.tpverp.backend.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "logo_documento_tienda")
public class StoreDocumentLogo {

    @Id
    private UUID id;

    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;

    @Column(name = "mime_type", nullable = false, length = 16)
    private String contentType;

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] contenido;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "creado_en", nullable = false)
    private Instant createdAt;

    protected StoreDocumentLogo() {
    }

    public StoreDocumentLogo(UUID storeId, String contentType, byte[] content,
            String sha256, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.contentType = Objects.requireNonNull(contentType, "contentType");
        this.contenido = Arrays.copyOf(Objects.requireNonNull(content, "content"), content.length);
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public UUID getId() { return id; }
    public UUID getStoreId() { return storeId; }
    public String getContentType() { return contentType; }
    public byte[] getContent() { return Arrays.copyOf(contenido, contenido.length); }
    public String getSha256() { return sha256; }
    public Instant getCreatedAt() { return createdAt; }
}
