package com.tpverp.backend.party;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberBalanceMovementRepository
        extends JpaRepository<MemberBalanceMovement, UUID> {

    List<MemberBalanceMovement> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    boolean existsByCustomerId(UUID customerId);
}
