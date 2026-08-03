package com.tpverp.backend.party;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberDocumentLoyaltyLineRepository
        extends JpaRepository<MemberDocumentLoyaltyLine, UUID> {

    List<MemberDocumentLoyaltyLine> findByDocumentId(UUID documentId);
}
