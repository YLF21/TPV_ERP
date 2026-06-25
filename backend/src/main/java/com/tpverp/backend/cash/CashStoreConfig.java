package com.tpverp.backend.cash;

import com.tpverp.backend.document.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "configuracion_caja_tienda")
public class CashStoreConfig {

    @Id
    @Column(name = "tienda_id")
    private UUID storeId;

    @Column(name = "tolerancia_descuadre", nullable = false, precision = 19, scale = 2)
    private BigDecimal discrepancyTolerance = Money.euros("0");

    @Column(name = "requiere_desglose_entrada", nullable = false)
    private boolean requireEntryBreakdown;

    @Column(name = "requiere_desglose_retirada", nullable = false)
    private boolean requireWithdrawalBreakdown;

    @Column(name = "requiere_desglose_cierre", nullable = false)
    private boolean requireClosingBreakdown;

    @Version
    private long version;

    protected CashStoreConfig() {
    }

    public CashStoreConfig(UUID storeId) {
        this.storeId = Objects.requireNonNull(storeId, "storeId");
    }

    public UUID getStoreId() {
        return storeId;
    }

    public BigDecimal getDiscrepancyTolerance() {
        return discrepancyTolerance;
    }
}
