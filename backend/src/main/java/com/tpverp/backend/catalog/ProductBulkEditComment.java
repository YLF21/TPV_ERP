package com.tpverp.backend.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "producto_edicion_masiva_comentario")
public class ProductBulkEditComment {

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "edicion_id", nullable = false)
    private ProductBulkEdit edicion;
    @Column(name = "usuario_id", nullable = false)
    private UUID usuarioId;
    @Column(nullable = false, length = 1000)
    private String texto;
    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    protected ProductBulkEditComment() {
    }

    ProductBulkEditComment(ProductBulkEdit edit, UUID userId, String text, Instant now) {
        id = UUID.randomUUID();
        edicion = Objects.requireNonNull(edit, "edit");
        usuarioId = Objects.requireNonNull(userId, "userId");
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("El comentario esta vacio");
        }
        texto = text.trim();
        if (texto.length() > 1000) {
            throw new IllegalArgumentException("El comentario supera 1000 caracteres");
        }
        creadoEn = Objects.requireNonNull(now, "now");
    }

    ProductBulkEditComment copyTo(ProductBulkEdit edit) {
        return new ProductBulkEditComment(edit, usuarioId, texto, creadoEn);
    }

    public UUID getId() { return id; }
    public UUID getUsuarioId() { return usuarioId; }
    public String getTexto() { return texto; }
    public Instant getCreadoEn() { return creadoEn; }
}
