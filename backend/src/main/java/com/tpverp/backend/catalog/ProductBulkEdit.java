package com.tpverp.backend.catalog;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "producto_edicion_masiva")
public class ProductBulkEdit {

    @Id
    private UUID id;
    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;
    @Column(nullable = false, length = 11)
    private String codigo;
    @Column(name = "serie_id", nullable = false)
    private UUID serieId;
    @Column(name = "numero_version", nullable = false)
    private int numeroVersion;
    @Column(name = "version_anterior_id")
    private UUID versionAnteriorId;
    @Column(nullable = false, length = 160)
    private String nombre;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProductBulkEditStatus estado = ProductBulkEditStatus.PENDING;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<ProductBulkEditContent.Row> contenido = new ArrayList<>();
    @Column(name = "creado_por", nullable = false)
    private UUID creadoPor;
    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;
    @Column(name = "actualizado_por", nullable = false)
    private UUID actualizadoPor;
    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;
    @Column(name = "aplicado_por")
    private UUID aplicadoPor;
    @Column(name = "aplicado_en")
    private Instant aplicadoEn;
    @OneToMany(mappedBy = "edicion", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("creadoEn asc")
    private List<ProductBulkEditComment> comentarios = new ArrayList<>();
    @Version
    private long version;

    protected ProductBulkEdit() {
    }

    public ProductBulkEdit(
            UUID storeId,
            String code,
            String name,
            List<ProductBulkEditContent.Row> content,
            UUID userId,
            Instant now) {
        id = UUID.randomUUID();
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        codigo = required(code, "code", 11);
        serieId = id;
        numeroVersion = 1;
        nombre = required(name, "name", 160);
        contenido = copyContent(content);
        creadoPor = Objects.requireNonNull(userId, "userId");
        creadoEn = Objects.requireNonNull(now, "now");
        actualizadoPor = userId;
        actualizadoEn = now;
    }

    public ProductBulkEdit nextVersion(
            String code,
            int versionNumber,
            String name,
            List<ProductBulkEditContent.Row> content,
            UUID userId,
            Instant now) {
        ProductBulkEdit next = new ProductBulkEdit(storeId, code, name, content, userId, now);
        next.serieId = serieId;
        if (versionNumber <= numeroVersion) {
            throw new IllegalArgumentException("La nueva version debe ser posterior");
        }
        next.numeroVersion = versionNumber;
        next.versionAnteriorId = id;
        comentarios.forEach(comment -> next.comentarios.add(comment.copyTo(next)));
        return next;
    }

    public void update(
            String name,
            List<ProductBulkEditContent.Row> content,
            UUID userId,
            Instant now) {
        nombre = required(name, "name", 160);
        contenido = copyContent(content);
        actualizadoPor = Objects.requireNonNull(userId, "userId");
        actualizadoEn = Objects.requireNonNull(now, "now");
        if (estado == ProductBulkEditStatus.APPLIED) {
            estado = ProductBulkEditStatus.PENDING;
            aplicadoPor = null;
            aplicadoEn = null;
        }
    }

    public void rename(String name, UUID userId, Instant now) {
        nombre = required(name, "name", 160);
        actualizadoPor = Objects.requireNonNull(userId, "userId");
        actualizadoEn = Objects.requireNonNull(now, "now");
    }

    public void apply(List<ProductBulkEditContent.Row> content, UUID userId, Instant now) {
        if (estado == ProductBulkEditStatus.APPLIED) {
            throw new IllegalStateException("La version ya esta aplicada");
        }
        contenido = copyContent(content);
        estado = ProductBulkEditStatus.APPLIED;
        aplicadoPor = Objects.requireNonNull(userId, "userId");
        aplicadoEn = Objects.requireNonNull(now, "now");
        actualizadoPor = userId;
        actualizadoEn = now;
    }

    public ProductBulkEditComment addComment(String text, UUID userId, Instant now) {
        ProductBulkEditComment comment = new ProductBulkEditComment(this, userId, text, now);
        comentarios.add(comment);
        actualizadoPor = userId;
        actualizadoEn = now;
        return comment;
    }

    public void recordNewVersion(UUID userId, Instant now) {
        actualizadoPor = Objects.requireNonNull(userId, "userId");
        actualizadoEn = Objects.requireNonNull(now, "now");
    }

    public void touch(UUID userId, Instant now) {
        actualizadoPor = Objects.requireNonNull(userId, "userId");
        actualizadoEn = Objects.requireNonNull(now, "now");
    }

    public UUID getId() { return id; }
    public UUID getStoreId() { return storeId; }
    public String getCodigo() { return codigo; }
    public UUID getSerieId() { return serieId; }
    public int getNumeroVersion() { return numeroVersion; }
    public UUID getVersionAnteriorId() { return versionAnteriorId; }
    public String getNombre() { return nombre; }
    public ProductBulkEditStatus getEstado() { return estado; }
    public List<ProductBulkEditContent.Row> getContenido() { return List.copyOf(contenido); }
    public UUID getCreadoPor() { return creadoPor; }
    public Instant getCreadoEn() { return creadoEn; }
    public UUID getActualizadoPor() { return actualizadoPor; }
    public Instant getActualizadoEn() { return actualizadoEn; }
    public UUID getAplicadoPor() { return aplicadoPor; }
    public Instant getAplicadoEn() { return aplicadoEn; }
    public List<ProductBulkEditComment> getComentarios() { return List.copyOf(comentarios); }
    public long getVersion() { return version; }

    private static List<ProductBulkEditContent.Row> copyContent(
            List<ProductBulkEditContent.Row> content) {
        return new ArrayList<>(ProductBulkEditContent.validateAndCopy(content));
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
