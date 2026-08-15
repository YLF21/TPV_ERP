package com.tpverp.backend.document.template;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "configuracion_origen_plantilla_documento")
@IdClass(DocumentTemplatePresentationSetting.Key.class)
public class DocumentTemplatePresentationSetting {

    @Id
    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 32)
    private DocumentTemplateType type;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "formato", nullable = false, length = 16)
    private DocumentTemplateFormat format;

    @Enumerated(EnumType.STRING)
    @Column(name = "origen", nullable = false, length = 16)
    private DocumentTemplateOrigin origin = DocumentTemplateOrigin.INTEGRATED;

    @Version
    private long version;

    protected DocumentTemplatePresentationSetting() {
    }

    public DocumentTemplatePresentationSetting(
            UUID storeId,
            DocumentTemplateType type,
            DocumentTemplateFormat format) {
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.type = Objects.requireNonNull(type, "type");
        this.format = Objects.requireNonNull(format, "format");
        requireSupported(type, format);
    }

    public UUID getStoreId() { return storeId; }
    public DocumentTemplateType getType() { return type; }
    public DocumentTemplateFormat getFormat() { return format; }
    public DocumentTemplateOrigin getOrigin() { return origin; }

    public void useOrigin(DocumentTemplateOrigin value) {
        origin = Objects.requireNonNull(value, "origin");
    }

    static void requireSupported(DocumentTemplateType type, DocumentTemplateFormat format) {
        if (!Objects.requireNonNull(format, "format")
                .supports(Objects.requireNonNull(type, "type"))) {
            throw new IllegalArgumentException("document_template_format_unsupported");
        }
    }

    public static final class Key implements Serializable {
        private UUID storeId;
        private DocumentTemplateType type;
        private DocumentTemplateFormat format;

        public Key() {
        }

        public Key(UUID storeId, DocumentTemplateType type, DocumentTemplateFormat format) {
            this.storeId = storeId;
            this.type = type;
            this.format = format;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key value)) return false;
            return Objects.equals(storeId, value.storeId)
                    && type == value.type && format == value.format;
        }

        @Override
        public int hashCode() {
            return Objects.hash(storeId, type, format);
        }
    }
}
