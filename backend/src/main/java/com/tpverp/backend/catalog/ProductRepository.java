package com.tpverp.backend.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    @Query("""
            select product from Product product
            where product.storeId = :storeId and product.activo = true
              and product.productType <> com.tpverp.backend.catalog.ProductType.SERVICE
              and (lower(product.nombre) like :pattern escape '!'
                or exists (select identifier.id from ProductIdentifier identifier
                  where identifier.productId = product.id and identifier.storeId = :storeId
                    and identifier.tipo in (com.tpverp.backend.catalog.IdentifierType.CODIGO,
                      com.tpverp.backend.catalog.IdentifierType.CODIGO_BARRAS)
                    and lower(identifier.valor) like :pattern escape '!'))
            order by case when exists (select exact.id from ProductIdentifier exact
                where exact.productId = product.id and exact.storeId = :storeId
                  and exact.tipo in (com.tpverp.backend.catalog.IdentifierType.CODIGO,
                    com.tpverp.backend.catalog.IdentifierType.CODIGO_BARRAS)
                  and lower(exact.valor) = :term) then 0 else 1 end,
              lower(product.nombre), product.id
            """)
    List<Product> searchAdjustmentProducts(@Param("storeId") UUID storeId,
            @Param("term") String term, @Param("pattern") String pattern, Pageable pageable);

    @Override
    Optional<Product> findById(UUID id);

    List<Product> findByStoreIdOrderByNombre(UUID storeId);

    @Query("""
            select product
            from Product product
            where product.storeId = :storeId
              and (:search is null
                or lower(product.nombre) like :search
                or lower(coalesce(product.descripcion, '')) like :search
                or lower(coalesce(product.comments, '')) like :search
                or exists (
                  select identifier.id
                  from ProductIdentifier identifier
                  where identifier.productId = product.id
                    and lower(identifier.valor) like :search
                ))
              and (:productType is null or product.productType = :productType)
              and (:priceUseMode is null or product.priceUseMode = :priceUseMode)
              and (:discountType is null or product.discountType = :discountType)
              and (:offersOnly = false
                or product.priceUseMode in (com.tpverp.backend.catalog.PriceUseMode.OFFER_PRICE, com.tpverp.backend.catalog.PriceUseMode.OFFER_DISCOUNT)
                or product.discountType = com.tpverp.backend.catalog.DiscountType.DISCOUNT_PRICE)
              and (:familyId is null or product.familyId = :familyId or product.subfamilyId = :familyId)
              and (:taxId is null or product.taxId = :taxId)
              and (:offerActive is null or product.offerActive = :offerActive)
              and (:cursorName is null
                or lower(product.nombre) > lower(coalesce(:cursorName, ''))
                or (lower(product.nombre) = lower(coalesce(:cursorName, '')) and product.id > :cursorId))
            order by lower(product.nombre), product.id
            """)
    List<Product> findPageByStoreId(
            @Param("storeId") UUID storeId,
            @Param("search") String search,
            @Param("productType") ProductType productType,
            @Param("priceUseMode") PriceUseMode priceUseMode,
            @Param("discountType") DiscountType discountType,
            @Param("offersOnly") boolean offersOnly,
            @Param("familyId") UUID familyId,
            @Param("taxId") UUID taxId,
            @Param("offerActive") Boolean offerActive,
            @Param("cursorName") String cursorName,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    List<Product> findAllByStoreIdAndIdIn(UUID storeId, Collection<UUID> ids);

    /** Loads the code needed by a remote history read after the persistence context has closed. */
    @Query("""
            select distinct product from Product product
            left join fetch product.identifiers
            where product.storeId = :storeId and product.id = :id
            """)
    Optional<Product> findWithIdentifiersByStoreIdAndId(@Param("storeId") UUID storeId, @Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select product from Product product where product.storeId = :storeId and product.id in :ids order by product.id")
    List<Product> findAllByStoreIdAndIdInForUpdate(
            @Param("storeId") UUID storeId, @Param("ids") Collection<UUID> ids);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Product product set product.familyId = :familyId, "
            + "product.subfamilyId = :subfamilyId, product.version = product.version + 1 "
            + "where product.storeId = :storeId and product.id in :ids")
    int moveClassification(
            @Param("storeId") UUID storeId,
            @Param("ids") Collection<UUID> ids,
            @Param("familyId") UUID familyId,
            @Param("subfamilyId") UUID subfamilyId);

    List<Product> findByFamilyId(UUID familyId);

    long countByFamilyId(UUID familyId);

    long countBySubfamilyId(UUID subfamilyId);

    @Modifying
    @Query("update Product product set product.familyId = :generalFamilyId, "
            + "product.subfamilyId = null, product.version = product.version + 1 "
            + "where product.familyId = :familyId")
    int reassignFamilyToGeneral(UUID familyId, UUID generalFamilyId);

    @Modifying
    @Query("update Product product set product.subfamilyId = null, "
            + "product.version = product.version + 1 where product.subfamilyId = :subfamilyId")
    int clearSubfamilyReferences(UUID subfamilyId);

    boolean existsByTaxId(UUID taxId);
}
