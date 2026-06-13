package com.tpverp.backend.document;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentoRelacionRepository
        extends JpaRepository<DocumentoRelacion, DocumentoRelacionId> {
}
