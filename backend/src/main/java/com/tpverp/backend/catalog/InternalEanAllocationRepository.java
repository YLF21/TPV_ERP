package com.tpverp.backend.catalog;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InternalEanAllocationRepository
        extends JpaRepository<InternalEanAllocation, UUID> {

    boolean existsByCompanyIdAndCodigo(UUID companyId, String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select allocation from InternalEanAllocation allocation where allocation.id = :id")
    Optional<InternalEanAllocation> findForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select allocation from InternalEanAllocation allocation
            where allocation.storeId = :storeId
              and allocation.formato = :format
              and allocation.estado = com.tpverp.backend.catalog.InternalEanAllocation.Status.RESERVADO
              and allocation.expiresAt <= :now
            order by allocation.expiresAt, allocation.reservedAt, allocation.id
            """)
    List<InternalEanAllocation> findExpiredForUpdate(
            @Param("storeId") UUID storeId,
            @Param("format") InternalEanFormat format,
            @Param("now") Instant now,
            Pageable pageable);
}
