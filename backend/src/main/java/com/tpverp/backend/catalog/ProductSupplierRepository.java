package com.tpverp.backend.catalog;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductSupplierRepository extends JpaRepository<ProductSupplier, UUID> {

    Optional<ProductSupplier> findByProduct_IdAndSupplier_Id(UUID productId, UUID supplierId);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into producto_proveedor as current_link (
                id, producto_id, proveedor_id, referencia_proveedor,
                ultima_fecha_entrada, version)
            values (:id, :productId, :supplierId, null, :entryDate, 0)
            on conflict (producto_id, proveedor_id) do update
            set ultima_fecha_entrada = greatest(
                    current_link.ultima_fecha_entrada,
                    excluded.ultima_fecha_entrada),
                version = current_link.version + 1
            """, nativeQuery = true)
    int upsertPurchase(
            @Param("id") UUID id,
            @Param("productId") UUID productId,
            @Param("supplierId") UUID supplierId,
            @Param("entryDate") LocalDate entryDate);

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
