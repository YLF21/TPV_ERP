package com.tpverp.saas.loyalty;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaasMemberWalletBootstrapStagingLotRepository
        extends JpaRepository<SaasMemberWalletBootstrapStagingLot, UUID> {

    boolean existsBySnapshot_IdAndLotId(UUID snapshotId, UUID lotId);

    long countBySnapshot_Id(UUID snapshotId);

    List<SaasMemberWalletBootstrapStagingLot> findBySnapshot_IdOrderByLotIdAsc(UUID snapshotId);

    @Query("""
            select lot
            from SaasMemberWalletBootstrapStagingLot lot
            where lot.snapshot.bootstrap.id = :bootstrapId
              and lot.snapshot.status = 'COMPLETED'
            order by lot.lotId
            """)
    List<SaasMemberWalletBootstrapStagingLot> findCompletedByBootstrap(
            @Param("bootstrapId") UUID bootstrapId);
}
