package com.tpverp.backend.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    @Override
    Optional<Product> findById(UUID id);

    List<Product> findByStoreIdOrderByNombre(UUID storeId);

    List<Product> findAllByStoreIdAndIdIn(UUID storeId, Collection<UUID> ids);

    List<Product> findByFamilyId(UUID familyId);

    boolean existsByTaxId(UUID taxId);
}
