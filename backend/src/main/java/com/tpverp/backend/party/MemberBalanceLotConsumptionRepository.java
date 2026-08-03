package com.tpverp.backend.party;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberBalanceLotConsumptionRepository
        extends JpaRepository<MemberBalanceLotConsumption, MemberBalanceLotConsumptionId> {

    List<MemberBalanceLotConsumption> findByMovement_Id(UUID movementId);

    List<MemberBalanceLotConsumption> findByLot_Id(UUID lotId);
}
