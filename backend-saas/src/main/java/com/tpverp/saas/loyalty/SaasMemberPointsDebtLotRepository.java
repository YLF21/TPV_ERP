package com.tpverp.saas.loyalty;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SaasMemberPointsDebtLotRepository extends JpaRepository<SaasMemberPointsDebtLot, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select l from SaasMemberPointsDebtLot l
            where l.companyId = :companyId
              and l.memberId = :memberId
              and l.status = :status
              and l.remainingAmount > 0
            order by l.createdSequence asc, l.id asc
            """)
    List<SaasMemberPointsDebtLot> findOutstandingForUpdate(
            @Param("companyId") UUID companyId,
            @Param("memberId") UUID memberId,
            @Param("status") MemberPointsDebtLotStatus status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select l from SaasMemberPointsDebtLot l
            where l.id = :id and l.companyId = :companyId
            """)
    Optional<SaasMemberPointsDebtLot> findForUpdateById(
            @Param("id") UUID id,
            @Param("companyId") UUID companyId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select l from SaasMemberPointsDebtLot l
            where l.companyId = :companyId
              and l.status = com.tpverp.saas.loyalty.MemberPointsDebtLotStatus.ACTIVE
              and l.remainingAmount > 0
            order by l.createdSequence asc, l.id asc
            """)
    List<SaasMemberPointsDebtLot> findCompanyOutstandingForUpdate(
            @Param("companyId") UUID companyId
    );
}
