package com.tpverp.backend.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductSupplierRepository extends JpaRepository<ProductSupplier, UUID> {

    Optional<ProductSupplier> findByProduct_IdAndSupplier_Id(UUID productId, UUID supplierId);

    boolean existsByProduct_IdAndPrincipalTrue(UUID productId);

    @Query("select max(link.lastEntryAt) from ProductSupplier link where link.product.id = :productId")
    Instant findLatestEntryAtForProduct(@Param("productId") UUID productId);

    @Query(value = "select id from producto where id = :productId for update", nativeQuery = true)
    UUID lockProduct(@Param("productId") UUID productId);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            update producto_proveedor
            set principal = false,
                version = version + 1
            where producto_id = :productId
              and proveedor_id <> :supplierId
              and principal = true
            """, nativeQuery = true)
    int clearPrincipal(
            @Param("productId") UUID productId,
            @Param("supplierId") UUID supplierId);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            update producto_proveedor
            set ultimo_proveedor = false,
                version = version + 1
            where producto_id = :productId
              and proveedor_id <> :supplierId
              and ultimo_proveedor = true
            """, nativeQuery = true)
    int clearLastSupplier(
            @Param("productId") UUID productId,
            @Param("supplierId") UUID supplierId);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            with product_lock as materialized (
                select pg_advisory_xact_lock(
                    hashtextextended(cast(:productId as text), 0))
            )
            insert into producto_proveedor as current_link (
                id, producto_id, proveedor_id, referencia_proveedor,
                principal, ultimo_proveedor, precio_compra_bruto, descuento_compra,
                ultima_entrada_en, version)
            select
                :id, :productId, :supplierId, :reference,
                :makePrincipal, :makeLastSupplier, :grossPurchasePrice, :purchaseDiscount,
                :entryAt, 0
            from product_lock
            on conflict (producto_id, proveedor_id) do update
            set referencia_proveedor = coalesce(
                    excluded.referencia_proveedor,
                    current_link.referencia_proveedor),
                ultimo_proveedor = current_link.ultimo_proveedor or :makeLastSupplier,
                principal = current_link.principal or :makePrincipal,
                precio_compra_bruto = case
                    when current_link.ultima_entrada_en is null
                      or excluded.ultima_entrada_en >= current_link.ultima_entrada_en
                    then excluded.precio_compra_bruto
                    else current_link.precio_compra_bruto
                end,
                descuento_compra = case
                    when current_link.ultima_entrada_en is null
                      or excluded.ultima_entrada_en >= current_link.ultima_entrada_en
                    then excluded.descuento_compra
                    else current_link.descuento_compra
                end,
                ultima_entrada_en = greatest(
                    current_link.ultima_entrada_en,
                    excluded.ultima_entrada_en),
                version = current_link.version + 1
            """, nativeQuery = true)
    int upsertPurchase(
            @Param("id") UUID id,
            @Param("productId") UUID productId,
            @Param("supplierId") UUID supplierId,
            @Param("reference") String reference,
            @Param("makePrincipal") boolean makePrincipal,
            @Param("makeLastSupplier") boolean makeLastSupplier,
            @Param("grossPurchasePrice") BigDecimal grossPurchasePrice,
            @Param("purchaseDiscount") BigDecimal purchaseDiscount,
            @Param("entryAt") Instant entryAt);

    default int upsertPurchase(
            UUID id,
            UUID productId,
            UUID supplierId,
            String reference,
            boolean makePrincipal,
            BigDecimal grossPurchasePrice,
            BigDecimal purchaseDiscount,
            Instant entryAt) {
        return upsertPurchase(
                id,
                productId,
                supplierId,
                reference,
                makePrincipal,
                true,
                grossPurchasePrice,
                purchaseDiscount,
                entryAt);
    }

    default int upsertPurchase(
            UUID id,
            UUID productId,
            UUID supplierId,
            String reference,
            BigDecimal grossPurchasePrice,
            BigDecimal purchaseDiscount,
            Instant entryAt) {
        return upsertPurchase(
                id,
                productId,
                supplierId,
                reference,
                false,
                grossPurchasePrice,
                purchaseDiscount,
                entryAt);
    }

    @Query("""
            select link
            from ProductSupplier link
            join fetch link.supplier supplier
            where link.product.id = :productId
              and link.product.storeId = :storeId
            order by supplier.documentNumber
            """)
    List<ProductSupplier> findForProduct(UUID productId, UUID storeId);

    @Query("""
            select link
            from ProductSupplier link
            join fetch link.supplier supplier
            join link.product product
            where supplier.id = :supplierId
              and supplier.company.id = :companyId
              and product.storeId = :storeId
            order by product.nombre, product.id
            """)
    List<ProductSupplier> findForSupplier(
            UUID supplierId, UUID companyId, UUID storeId);

    @Query("""
            select link
            from ProductSupplier link
            join fetch link.supplier supplier
            join link.product product
            where product.storeId = :storeId
            order by product.id, supplier.documentNumber, supplier.id
            """)
    List<ProductSupplier> findForStore(UUID storeId);

    @Query("""
            select link
            from ProductSupplier link
            join fetch link.supplier supplier
            join link.product product
            where product.storeId = :storeId
              and product.id in :productIds
            order by product.id, supplier.documentNumber
            """)
    List<ProductSupplier> findForProducts(UUID storeId, Collection<UUID> productIds);

    @Query("""
            select link.product.id
            from ProductSupplier link
            where link.supplier.id = :supplierId
              and link.product.id in :productIds
            """)
    List<UUID> findLinkedProductIds(UUID supplierId, Collection<UUID> productIds);
}
