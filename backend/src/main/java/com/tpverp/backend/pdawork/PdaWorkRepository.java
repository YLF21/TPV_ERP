package com.tpverp.backend.pdawork;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PdaWorkRepository extends JpaRepository<PdaWorkItem, UUID> {
    List<PdaWorkItem> findByStoreIdOrderByCreatedAtDesc(UUID storeId);
    List<PdaWorkItem> findByStoreIdAndStatusOrderByCreatedAtDesc(UUID storeId, PdaWorkStatus status);
    Optional<PdaWorkItem> findByIdAndStoreId(UUID id, UUID storeId);
}