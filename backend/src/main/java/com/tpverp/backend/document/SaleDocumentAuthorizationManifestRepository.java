package com.tpverp.backend.document;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaleDocumentAuthorizationManifestRepository
        extends JpaRepository<SaleDocumentAuthorizationManifest, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select manifest
            from SaleDocumentAuthorizationManifest manifest
            where manifest.documentId = :documentId
              and manifest.storeId = :storeId
            """)
    Optional<SaleDocumentAuthorizationManifest> findForUpdate(
            @Param("documentId") UUID documentId,
            @Param("storeId") UUID storeId);
}
