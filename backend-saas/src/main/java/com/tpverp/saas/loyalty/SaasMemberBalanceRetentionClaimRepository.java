package com.tpverp.saas.loyalty;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasMemberBalanceRetentionClaimRepository
        extends JpaRepository<SaasMemberBalanceRetentionClaim, UUID> {

    List<SaasMemberBalanceRetentionClaim> findByReservation_IdOrderByLotIdAsc(UUID reservationId);

    List<SaasMemberBalanceRetentionClaim> findByReceipt_OperationIdOrderByLotIdAsc(UUID operationId);

    Optional<SaasMemberBalanceRetentionClaim> findFirstByLotIdAndSourceMovementIdAndStatusIn(
            UUID lotId,
            UUID sourceMovementId,
            List<SaasMemberBalanceRetentionClaimStatus> statuses);

    List<SaasMemberBalanceRetentionClaim> findByLotIdAndSourceMovementIdAndStatusIn(
            UUID lotId,
            UUID sourceMovementId,
            List<SaasMemberBalanceRetentionClaimStatus> statuses);

    List<SaasMemberBalanceRetentionClaim> findByLotIdAndStatusIn(
            UUID lotId,
            List<SaasMemberBalanceRetentionClaimStatus> statuses);

    Optional<SaasMemberBalanceRetentionClaim> findFirstByLotIdAndStatusIn(
            UUID lotId,
            List<SaasMemberBalanceRetentionClaimStatus> statuses);
}
