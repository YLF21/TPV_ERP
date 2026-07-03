package com.tpverp.backend.party;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberMovementRepository extends JpaRepository<MemberMovement, UUID> {

    List<MemberMovement> findByMemberIdOrderByCreatedAtDesc(UUID memberId);

    boolean existsBySourceEventId(UUID sourceEventId);
}
