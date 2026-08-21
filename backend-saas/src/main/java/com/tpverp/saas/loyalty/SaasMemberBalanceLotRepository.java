package com.tpverp.saas.loyalty;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasMemberBalanceLotRepository extends JpaRepository<SaasMemberBalanceLot, UUID> {

    List<SaasMemberBalanceLot> findByAccount_IdOrderByCreatedAtAscIdAsc(UUID accountId);

    Optional<SaasMemberBalanceLot> findByCompanyIdAndSourceMovementId(
            UUID companyId,
            UUID sourceMovementId);

    List<SaasMemberBalanceLot> findByCompanyIdOrderByIdAsc(UUID companyId);
}
