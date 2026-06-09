package com.tpverp.backend.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProductSupplierRepository extends JpaRepository<ProductSupplier, UUID> {

    Optional<ProductSupplier> findByProductIdAndSupplierId(UUID productId, UUID supplierId);

    @Query("""
            select link
            from ProductSupplier link
            join fetch link.supplier supplier
            where link.product.id = :productId
              and link.product.storeId = :storeId
            order by supplier.documentNumber
            """)
    List<ProductSupplier> findForProduct(UUID productId, UUID storeId);
}
