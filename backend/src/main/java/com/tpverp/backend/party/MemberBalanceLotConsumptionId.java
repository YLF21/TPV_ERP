package com.tpverp.backend.party;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class MemberBalanceLotConsumptionId implements Serializable {

    private UUID movementId;
    private UUID lotId;

    protected MemberBalanceLotConsumptionId() {
    }

    public MemberBalanceLotConsumptionId(UUID movementId, UUID lotId) {
        this.movementId = movementId;
        this.lotId = lotId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MemberBalanceLotConsumptionId value)) {
            return false;
        }
        return Objects.equals(movementId, value.movementId)
                && Objects.equals(lotId, value.lotId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(movementId, lotId);
    }
}
