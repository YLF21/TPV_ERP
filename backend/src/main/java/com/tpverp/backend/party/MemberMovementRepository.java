package com.tpverp.backend.party;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;

public interface MemberMovementRepository extends JpaRepository<MemberMovement, UUID> {

    List<MemberMovement> findByMemberIdOrderByCreatedAtDesc(UUID memberId);

    List<MemberMovement> findByDocumentIdOrderByCreatedAtAsc(UUID documentId);

    boolean existsBySourceEventId(UUID sourceEventId);

    @Query("""
            select movement from MemberMovement movement
            where movement.company.id = :companyId
              and movement.type = com.tpverp.backend.party.MemberMovementType.CAMBIO_CATEGORIA
              and movement.createdAt = (
                  select max(candidate.createdAt)
                  from MemberMovement candidate
                  where candidate.member.id = movement.member.id
                    and candidate.type = com.tpverp.backend.party.MemberMovementType.CAMBIO_CATEGORIA
              )
            order by movement.member.id, movement.id
            """)
    List<MemberMovement> findLatestCategoryMovements(
            @Param("companyId") UUID companyId);
}
