package com.tpverp.backend.party;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "member_balance_lot_consumption")
public class MemberBalanceLotConsumption {

    @EmbeddedId
    private MemberBalanceLotConsumptionId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("movementId")
    @JoinColumn(name = "movement_id", nullable = false)
    private MemberMovement movement;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("lotId")
    @JoinColumn(name = "lot_id", nullable = false)
    private MemberBalanceLot lot;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    protected MemberBalanceLotConsumption() {
    }

    public MemberBalanceLotConsumption(MemberMovement movement, MemberBalanceLot lot, BigDecimal amount) {
        this.id = new MemberBalanceLotConsumptionId(movement.getId(), lot.getId());
        this.movement = movement;
        this.lot = lot;
        this.amount = PartyValues.money(amount);
    }
}
