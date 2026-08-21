package com.tpverp.backend.party.loyalty.points.bootstrap;

import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberPointsBootstrapSnapshotOperationRepository
        extends JpaRepository<MemberPointsBootstrapSnapshotOperation, UUID> {
    @Query("""
            select operation from MemberPointsBootstrapSnapshotOperation operation
            where operation.snapshotId = :snapshotId
            order by cast(operation.operationId as string)
            """)
    Slice<MemberPointsBootstrapSnapshotOperation> findChunk(
            @Param("snapshotId") UUID snapshotId, Pageable pageable);

    @Query("""
            select operation from MemberPointsBootstrapSnapshotOperation operation
            where operation.snapshotId = :snapshotId
              and operation.sourceSequence <= :cutSequence
            order by cast(operation.operationId as string)
            """)
    Slice<MemberPointsBootstrapSnapshotOperation> findAbsorbedChunk(
            @Param("snapshotId") UUID snapshotId,
            @Param("cutSequence") long cutSequence,
            Pageable pageable);

    @Query("""
            select operation from MemberPointsBootstrapSnapshotOperation operation
            where operation.snapshotId = :snapshotId
              and operation.sourceSequence > :cutSequence
            order by cast(operation.operationId as string)
            """)
    Slice<MemberPointsBootstrapSnapshotOperation> findReplayChunk(
            @Param("snapshotId") UUID snapshotId,
            @Param("cutSequence") long cutSequence,
            Pageable pageable);
}
