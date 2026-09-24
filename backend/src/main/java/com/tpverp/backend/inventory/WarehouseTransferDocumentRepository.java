package com.tpverp.backend.inventory;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface WarehouseTransferDocumentRepository extends JpaRepository<WarehouseTransferDocument, UUID>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<WarehouseTransferDocument> {
    @Query("""
            select value from WarehouseTransferDocument value
            where value.storeId = :storeId and (:status is null or value.status = :status)
            order by value.createdAt desc, value.id desc
            """)
    Page<WarehouseTransferDocument> page(UUID storeId, WarehouseTransferDocument.Status status, Pageable pageable);
    default Page<WarehouseTransferDocument> pageFiltered(UUID storeId, WarehouseTransferDocument.Status status,
            UUID sourceWarehouseId, UUID targetWarehouseId, Instant from, Instant before,
            String search, Pageable pageable) {
        org.springframework.data.jpa.domain.Specification<WarehouseTransferDocument> filters = (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("storeId"), storeId));
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (sourceWarehouseId != null) predicates.add(cb.equal(root.get("sourceWarehouseId"), sourceWarehouseId));
            if (targetWarehouseId != null) predicates.add(cb.equal(root.get("targetWarehouseId"), targetWarehouseId));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), from));
            if (before != null) predicates.add(cb.lessThan(root.<Instant>get("createdAt"), before));
            if (search != null) predicates.add(cb.or(
                    cb.like(cb.lower(root.get("number")), search, '!'),
                    cb.like(cb.lower(root.get("notes")), search, '!')));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        var orderedPage = org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(),
                pageable.getPageSize(), org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "createdAt", "id"));
        return findAll(filters, orderedPage);
    }
    Optional<WarehouseTransferDocument> findByIdAndStoreId(UUID id, UUID storeId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from WarehouseTransferDocument value where value.id = :id and value.storeId = :storeId")
    Optional<WarehouseTransferDocument> findLockedByIdAndStoreId(UUID id, UUID storeId);
}
