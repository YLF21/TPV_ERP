package com.tpverp.backend.document;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentOperationalEventRepository extends JpaRepository<DocumentOperationalEvent, UUID> {

    List<DocumentOperationalEvent> findAllByDocumentIdOrderByOccurredAtAscIdAsc(UUID documentId);
}
