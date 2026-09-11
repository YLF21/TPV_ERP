package com.tpverp.backend.catalog;

import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductIdentifierRepository extends JpaRepository<ProductIdentifier, UUID> {

    Optional<ProductIdentifier> findByStoreIdAndValor(UUID storeId, String value);

    List<ProductIdentifier> findAllByStoreIdAndValor(UUID storeId, String value);

    @org.springframework.data.jpa.repository.Query("""
            select identifier
            from ProductIdentifier identifier
            where identifier.storeId = :storeId
              and lower(identifier.valor) in :values
            """)
    List<ProductIdentifier> findAllByStoreIdAndValorLowerIn(
            @org.springframework.data.repository.query.Param("storeId") UUID storeId,
            @org.springframework.data.repository.query.Param("values") Collection<String> values);

    boolean existsByStoreIdAndValorAndProductIdNot(UUID storeId, String value, UUID productId);
}
