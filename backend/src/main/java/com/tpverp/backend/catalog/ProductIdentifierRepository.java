package com.tpverp.backend.catalog;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductIdentifierRepository extends JpaRepository<ProductIdentifier, UUID> {

    Optional<ProductIdentifier> findByStoreIdAndValor(UUID storeId, String value);

    boolean existsByStoreIdAndValorAndProductIdNot(UUID storeId, String value, UUID productId);
}
