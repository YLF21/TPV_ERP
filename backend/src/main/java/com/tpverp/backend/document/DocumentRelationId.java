package com.tpverp.backend.document;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class DocumentRelationId implements Serializable {

    private UUID documento;
    private UUID origen;

    protected DocumentRelationId() {
    }

    public DocumentRelationId(UUID documento, UUID origen) {
        this.documento = documento;
        this.origen = origen;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof DocumentRelationId id
                && Objects.equals(documento, id.documento)
                && Objects.equals(origen, id.origen);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documento, origen);
    }
}
