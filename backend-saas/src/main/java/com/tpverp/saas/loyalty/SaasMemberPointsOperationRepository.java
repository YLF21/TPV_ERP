package com.tpverp.saas.loyalty;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SaasMemberPointsOperationRepository extends JpaRepository<SaasMemberPointsOperation, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select o from SaasMemberPointsOperation o
            where o.companyId = :companyId and o.operationId = :operationId
            """)
    Optional<SaasMemberPointsOperation> findForUpdateByOperationId(
            @Param("companyId") UUID companyId,
            @Param("operationId") UUID operationId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select o from SaasMemberPointsOperation o
            where o.companyId = :companyId and o.storeId = :storeId and o.storeSequence = :storeSequence
            """)
    Optional<SaasMemberPointsOperation> findForUpdateByStoreSequence(
            @Param("companyId") UUID companyId,
            @Param("storeId") UUID storeId,
            @Param("storeSequence") Long storeSequence);

    Optional<SaasMemberPointsOperation> findByCompanyIdAndOperationId(UUID companyId, UUID operationId);

    List<SaasMemberPointsOperation> findByCompanyIdAndOperationIdIn(UUID companyId, List<UUID> operationIds);

    long countByCompanyIdAndStatus(UUID companyId, MemberPointsOperationStatus status);

    @Query("select coalesce(max(o.id), 0) from SaasMemberPointsOperation o where o.companyId = :companyId")
    long maxCentralId(@Param("companyId") UUID companyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<SaasMemberPointsOperation> findByCompanyIdAndStatusOrderByIdAsc(
            UUID companyId,
            MemberPointsOperationStatus status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<SaasMemberPointsOperation> findByCompanyIdAndMemberIdAndStatusOrderByIdAsc(
            UUID companyId,
            UUID memberId,
            MemberPointsOperationStatus status
    );
}
