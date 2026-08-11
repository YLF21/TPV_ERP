package com.tpverp.backend.organization;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreDocumentLogoRepository extends JpaRepository<StoreDocumentLogo, UUID> {
    Optional<StoreDocumentLogo> findByIdAndStoreId(UUID id, UUID storeId);
    Optional<StoreDocumentLogo> findByStoreIdAndSha256(UUID storeId, String sha256);
}
