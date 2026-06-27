package com.tpverp.backend.document;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;

@Entity
@Table(name = "documento_relacion")
@IdClass(DocumentRelationId.class)
public class DocumentRelation {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "documento_id", nullable = false)
    private CommercialDocument documento;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "origen_id", nullable = false)
    private CommercialDocument origen;

    @Enumerated(EnumType.STRING)
    private DocumentRelationType tipo;

    protected DocumentRelation() {
    }

    public DocumentRelation(
            CommercialDocument documento,
            CommercialDocument origen,
            DocumentRelationType tipo) {
        this.documento = Objects.requireNonNull(documento, "documento");
        this.origen = Objects.requireNonNull(origen, "origen");
        this.tipo = Objects.requireNonNull(tipo, "tipo");
        if (documento == origen || documento.getId().equals(origen.getId())) {
            throw new IllegalArgumentException("un documento no puede relacionarse consigo mismo");
        }
    }
}
