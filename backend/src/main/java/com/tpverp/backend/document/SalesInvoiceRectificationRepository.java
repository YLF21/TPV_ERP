package com.tpverp.backend.document;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesInvoiceRectificationRepository
        extends JpaRepository<SalesInvoiceRectification, UUID> {

    Optional<SalesInvoiceRectification> findByDocumentId(UUID documentId);

    List<SalesInvoiceRectification> findByDocumentIdIn(Collection<UUID> documentIds);
}
