package com.tpverp.saas.loyalty;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "saas_member_balance_reservation_lot")
public class SaasMemberBalanceReservationLot {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private SaasMemberBalanceReservation reservation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lot_id", nullable = false)
    private SaasMemberBalanceLot lot;

    @Column(name = "reserved_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal reservedAmount;

    @Column(name = "consumed_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal consumedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "balance_type", nullable = false, length = 20)
    private MemberBalanceType balanceType;

    @Version
    private long version;

    protected SaasMemberBalanceReservationLot() {
    }

    public SaasMemberBalanceReservationLot(
            UUID id,
            SaasMemberBalanceReservation reservation,
            SaasMemberBalanceLot lot,
            BigDecimal reservedAmount) {
        this.id = id;
        this.reservation = reservation;
        this.lot = lot;
        this.reservedAmount = reservedAmount;
        this.consumedAmount = BigDecimal.ZERO.setScale(2);
        this.balanceType = lot.getBalanceType();
    }

    public SaasMemberBalanceLot getLot() {
        return lot;
    }

    public BigDecimal getReservedAmount() {
        return reservedAmount;
    }

    public BigDecimal getConsumedAmount() {
        return consumedAmount;
    }

    public BigDecimal getRemainingAmount() {
        return reservedAmount.subtract(consumedAmount);
    }

    public MemberBalanceType getBalanceType() {
        return balanceType;
    }

    public void consume(BigDecimal amount) {
        BigDecimal result = consumedAmount.add(amount);
        if (result.compareTo(reservedAmount) > 0) {
            throw new IllegalStateException("El lote reservado no cubre el consumo solicitado");
        }
        consumedAmount = result;
    }
}
