package com.tpverp.backend.party;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberDocumentLoyaltySettlementRepository
        extends JpaRepository<MemberDocumentLoyaltySettlement, UUID> {
}
