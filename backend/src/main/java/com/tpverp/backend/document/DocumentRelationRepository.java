package com.tpverp.backend.document;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRelationRepository
        extends JpaRepository<DocumentRelation, DocumentRelationId> {

    boolean existsByOrigen_IdAndTipo(UUID originId, DocumentRelationType type);
}
