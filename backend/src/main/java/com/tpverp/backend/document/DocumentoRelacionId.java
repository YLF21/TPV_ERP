package com.tpverp.backend.document;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class DocumentoRelacionId implements Serializable {

    private UUID documento;
    private UUID origen;

    protected DocumentoRelacionId() {
    }

    public DocumentoRelacionId(UUID documento, UUID origen) {
        this.documento = documento;
        this.origen = origen;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof DocumentoRelacionId id
                && Objects.equals(documento, id.documento)
                && Objects.equals(origen, id.origen);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documento, origen);
    }
}
