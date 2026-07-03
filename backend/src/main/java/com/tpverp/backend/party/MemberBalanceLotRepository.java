package com.tpverp.backend.party;

import java.util.UUID;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberBalanceLotRepository extends JpaRepository<MemberBalanceLot, UUID> {

    List<MemberBalanceLot> findByMemberIdAndAmountRemainingGreaterThan(UUID memberId, BigDecimal amount);

    List<MemberBalanceLot> findByExpiresAtBeforeAndExpiredAtIsNullAndAmountRemainingGreaterThan(
            Instant now, BigDecimal amount);
}
