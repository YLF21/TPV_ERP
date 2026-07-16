package com.tpverp.backend.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, UUID> {

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

    List<Product> findByFamilyId(UUID familyId);

    boolean existsByTaxId(UUID taxId);
}
