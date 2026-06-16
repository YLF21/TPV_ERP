package com.tpverp.backend.document;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentoRelacionRepository
        extends JpaRepository<DocumentoRelacion, DocumentoRelacionId> {

    boolean existsByOrigen_IdAndTipo(UUID originId, TipoRelacionDocumento type);
}
