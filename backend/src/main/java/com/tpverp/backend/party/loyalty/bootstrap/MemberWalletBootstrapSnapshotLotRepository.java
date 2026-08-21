package com.tpverp.backend.party.loyalty.bootstrap;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberWalletBootstrapSnapshotLotRepository
        extends JpaRepository<MemberWalletBootstrapSnapshotLot, UUID> {

    @Query("""
            select lot
            from MemberWalletBootstrapSnapshotLot lot
            where lot.snapshot.snapshotId = :snapshotId
            order by cast(lot.lotId as string)
            """)
    List<MemberWalletBootstrapSnapshotLot> findBySnapshot_SnapshotIdOrderByLotId(
            @Param("snapshotId") UUID snapshotId,
            Pageable pageable);
}
