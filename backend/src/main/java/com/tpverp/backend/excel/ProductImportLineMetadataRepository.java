package com.tpverp.backend.excel;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductImportLineMetadataRepository
        extends JpaRepository<ProductImportLineMetadata, UUID> {

    List<ProductImportLineMetadata> findByDocumentId(UUID documentId);

    void deleteByDocumentId(UUID documentId);
}
