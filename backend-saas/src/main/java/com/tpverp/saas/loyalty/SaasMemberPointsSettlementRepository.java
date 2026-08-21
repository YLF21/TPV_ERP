package com.tpverp.saas.loyalty;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SaasMemberPointsSettlementRepository extends JpaRepository<SaasMemberPointsSettlement, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from SaasMemberPointsSettlement s
            where s.companyId = :companyId
              and s.documentId = :documentId
              and s.settlementType = :settlementType
            """)
    Optional<SaasMemberPointsSettlement> findForUpdateByDocument(
            @Param("companyId") UUID companyId,
            @Param("documentId") UUID documentId,
            @Param("settlementType") MemberPointsSettlementType settlementType
    );
}
