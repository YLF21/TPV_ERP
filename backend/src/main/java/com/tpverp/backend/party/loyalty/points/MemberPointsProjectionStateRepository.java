package com.tpverp.backend.party.loyalty.points;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberPointsProjectionStateRepository
        extends JpaRepository<MemberPointsProjectionState, UUID> {
    @Modifying
    @Query(value = """
            insert into member_points_projection_state (
                tienda_id, empresa_id, status, last_sequence,
                projected_through_sequence, official_through_sequence, version
            ) values (:storeId, :companyId, 'LOCAL_ACTIVE', 0, 0, 0, 0)
            on conflict (tienda_id) do nothing
            """, nativeQuery = true)
    int insertIfMissing(
            @Param("companyId") UUID companyId,
            @Param("storeId") UUID storeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select state from MemberPointsProjectionState state where state.storeId = :storeId")
    Optional<MemberPointsProjectionState> findLockedByStoreId(
            @Param("storeId") UUID storeId);
}
