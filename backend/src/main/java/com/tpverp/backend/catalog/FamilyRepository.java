package com.tpverp.backend.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FamilyRepository extends JpaRepository<Family, UUID> {

    List<Family> findByStoreIdOrderByNombre(UUID storeId);

    Optional<Family> findByStoreIdAndPredeterminadaTrue(UUID storeId);
}
