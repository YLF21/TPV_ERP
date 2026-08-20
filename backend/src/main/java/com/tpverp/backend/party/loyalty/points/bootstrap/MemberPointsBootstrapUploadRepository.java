package com.tpverp.backend.party.loyalty.points.bootstrap;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberPointsBootstrapUploadRepository
        extends JpaRepository<MemberPointsBootstrapUpload, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select upload from MemberPointsBootstrapUpload upload
            where upload.snapshotId = :snapshotId
            """)
    Optional<MemberPointsBootstrapUpload> findForUpdate(
            @Param("snapshotId") UUID snapshotId);

    Optional<MemberPointsBootstrapUpload> findByBootstrapIdAndStoreId(
            UUID bootstrapId, UUID storeId);
}
