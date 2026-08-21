package com.tpverp.backend.party;

import java.util.UUID;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberBalanceLotRepository extends JpaRepository<MemberBalanceLot, UUID> {

    List<MemberBalanceLot> findByMemberIdAndAmountRemainingGreaterThan(UUID memberId, BigDecimal amount);

    List<MemberBalanceLot> findBySourceMovement_Id(UUID movementId);

    List<MemberBalanceLot> findByExpiresAtBeforeAndExpiredAtIsNullAndAmountRemainingGreaterThan(
            Instant now, BigDecimal amount);

    @Query("""
            select lot.id as lotId,
                   lot.member.id as memberId,
                   lot.balanceType as balanceType,
                   lot.amountOriginal as originalAmount,
                   lot.amountRemaining as remainingAmount,
                   lot.createdAt as createdAt,
                   lot.expiresAt as expiresAt,
                   sourceMovement.id as sourceMovementId,
                   lot.documentId as documentId
            from MemberBalanceLot lot
            left join lot.sourceMovement sourceMovement
            where lot.member.company.id = :companyId
            order by cast(lot.id as string)
            """)
    Slice<MemberWalletSnapshotLotProjection> findWalletSnapshotLots(
            @Param("companyId") UUID companyId,
            Pageable pageable);

    interface MemberWalletSnapshotLotProjection {
        UUID getLotId();

        UUID getMemberId();

        MemberBalanceLotType getBalanceType();

        BigDecimal getOriginalAmount();

        BigDecimal getRemainingAmount();

        Instant getCreatedAt();

        Instant getExpiresAt();

        UUID getSourceMovementId();

        UUID getDocumentId();
    }
}
