package com.tpverp.saas.loyalty;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SaasMemberPointsDebtAllocationRepository extends JpaRepository<SaasMemberPointsDebtAllocation, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select a from SaasMemberPointsDebtAllocation a
            where a.saleSettlementId = :settlementId
            order by a.createdAt asc, a.id asc
            """)
    List<SaasMemberPointsDebtAllocation> findForUpdateBySettlementId(
            @Param("settlementId") UUID settlementId
    );
}
