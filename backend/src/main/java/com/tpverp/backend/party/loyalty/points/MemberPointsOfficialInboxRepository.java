package com.tpverp.backend.party.loyalty.points;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberPointsOfficialInboxRepository
        extends JpaRepository<MemberPointsOfficialInbox, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select item from MemberPointsOfficialInbox item
            where item.storeId = :storeId and item.appliedAt is null
            order by case when item.lastAttemptAt is null then 0 else 1 end,
                     item.lastAttemptAt,
                     item.officialRevision,
                     item.memberId
            """)
    List<MemberPointsOfficialInbox> findPendingForUpdate(
            @Param("storeId") UUID storeId,
            Pageable pageable);
}
