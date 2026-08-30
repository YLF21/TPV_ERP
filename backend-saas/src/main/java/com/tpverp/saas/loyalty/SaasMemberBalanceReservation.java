package com.tpverp.saas.loyalty;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "saas_member_balance_reservation")
public class SaasMemberBalanceReservation {

    public static final String ACTIVE = "ACTIVE";
    public static final String PREPARED = "PREPARED";
    public static final String RELEASED = "RELEASED";
    public static final String EXPIRED = "EXPIRED";
    public static final String CONSUMED = "CONSUMED";

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private SaasMemberBalanceAccount account;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "installation_id", nullable = false)
    private UUID installationId;

    @Column(name = "terminal_id", nullable = false, length = 120)
    private String terminalId;

    @Column(name = "sale_id", nullable = false, length = 120)
    private String saleId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "reserved_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal reservedTotal;

    @Column(name = "reserved_loyalty_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal reservedLoyaltyAmount;

    @Column(name = "reserved_return_credit_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal reservedReturnCreditAmount;

    @Column(name = "prepared_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal preparedAmount;

    @Column(name = "prepared_loyalty_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal preparedLoyaltyAmount;

    @Column(name = "prepared_return_credit_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal preparedReturnCreditAmount;

    @Column(name = "prepare_operation_id")
    private UUID prepareOperationId;

    @Column(name = "prepared_at")
    private Instant preparedAt;

    @Column(name = "consumed_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal consumedTotal;

    @Column(name = "consumed_loyalty_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal consumedLoyaltyAmount;

    @Column(name = "consumed_return_credit_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal consumedReturnCreditAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "heartbeat_at", nullable = false)
    private Instant heartbeatAt;

    @Column(name = "lease_expires_at", nullable = false)
    private Instant leaseExpiresAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "retention_revision", nullable = false)
    private long retentionRevision;

    @Column(name = "retention_fingerprint", nullable = false, length = 128)
    private String retentionFingerprint;

    @Column(name = "retention_attributed_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal retentionAttributedAmount;

    @Version
    private long version;

    protected SaasMemberBalanceReservation() {
    }

    public SaasMemberBalanceReservation(
            UUID id,
            SaasMemberBalanceAccount account,
            UUID storeId,
            UUID installationId,
            String terminalId,
            String saleId,
            BigDecimal reservedTotal,
            Instant now,
            Duration leaseDuration) {
        this(
                id,
                account,
                storeId,
                installationId,
                terminalId,
                saleId,
                reservedTotal,
                BigDecimal.ZERO.setScale(2),
                now,
                leaseDuration);
    }

    public SaasMemberBalanceReservation(
            UUID id,
            SaasMemberBalanceAccount account,
            UUID storeId,
            UUID installationId,
            String terminalId,
            String saleId,
            BigDecimal reservedLoyaltyAmount,
            BigDecimal reservedReturnCreditAmount,
            Instant now,
            Duration leaseDuration) {
        BigDecimal loyalty = nonNegativeMoney(reservedLoyaltyAmount, "reservedLoyaltyAmount");
        BigDecimal returnCredit = nonNegativeMoney(reservedReturnCreditAmount,
                "reservedReturnCreditAmount");
        this.id = id;
        this.account = account;
        this.storeId = storeId;
        this.installationId = installationId;
        this.terminalId = terminalId;
        this.saleId = saleId;
        this.status = ACTIVE;
        // V17's legacy alias represents the LOYALTY bucket. Typed V2 callers
        // must use the two explicit buckets when they need the wallet total.
        this.reservedTotal = loyalty;
        this.reservedLoyaltyAmount = loyalty;
        this.reservedReturnCreditAmount = returnCredit;
        this.preparedAmount = BigDecimal.ZERO.setScale(2);
        this.preparedLoyaltyAmount = BigDecimal.ZERO.setScale(2);
        this.preparedReturnCreditAmount = BigDecimal.ZERO.setScale(2);
        this.consumedTotal = BigDecimal.ZERO.setScale(2);
        this.consumedLoyaltyAmount = BigDecimal.ZERO.setScale(2);
        this.consumedReturnCreditAmount = BigDecimal.ZERO.setScale(2);
        this.retentionRevision = 0L;
        this.retentionFingerprint = "";
        this.retentionAttributedAmount = BigDecimal.ZERO.setScale(2);
        this.createdAt = now;
        renew(now, leaseDuration);
    }

    public UUID getId() {
        return id;
    }

    public SaasMemberBalanceAccount getAccount() {
        return account;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getInstallationId() {
        return installationId;
    }

    public String getTerminalId() {
        return terminalId;
    }

    public String getSaleId() {
        return saleId;
    }

    public String getStatus() {
        return status;
    }

    public BigDecimal getReservedTotal() {
        return reservedTotal;
    }

    public BigDecimal getReservedLoyaltyAmount() {
        return reservedLoyaltyAmount;
    }

    public BigDecimal getReservedReturnCreditAmount() {
        return reservedReturnCreditAmount;
    }

    public BigDecimal getConsumedTotal() {
        return consumedTotal;
    }

    public BigDecimal getConsumedLoyaltyAmount() {
        return consumedLoyaltyAmount;
    }

    public BigDecimal getConsumedReturnCreditAmount() {
        return consumedReturnCreditAmount;
    }

    public BigDecimal getPreparedAmount() {
        return preparedAmount;
    }

    public BigDecimal getPreparedLoyaltyAmount() {
        return preparedLoyaltyAmount;
    }

    public BigDecimal getPreparedReturnCreditAmount() {
        return preparedReturnCreditAmount;
    }

    public UUID getPrepareOperationId() {
        return prepareOperationId;
    }

    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public long getRetentionRevision() {
        return retentionRevision;
    }

    public String getRetentionFingerprint() {
        return retentionFingerprint;
    }

    public BigDecimal getRetentionAttributedAmount() {
        return retentionAttributedAmount;
    }

    public void incorporateWalletLot(MemberBalanceType balanceType, BigDecimal amount) {
        BigDecimal normalized = amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException("El lote incorporado debe tener importe positivo");
        }
        if (balanceType == MemberBalanceType.LOYALTY) {
            reservedTotal = reservedTotal.add(normalized);
            reservedLoyaltyAmount = reservedLoyaltyAmount.add(normalized);
        } else {
            reservedReturnCreditAmount = reservedReturnCreditAmount.add(normalized);
        }
    }

    public void configureRetention(long revision, String fingerprint) {
        configureRetention(revision, fingerprint, BigDecimal.ZERO.setScale(2));
    }

    public void configureRetention(long revision, String fingerprint, BigDecimal attributedAmount) {
        if (revision < 0 || fingerprint == null || fingerprint.length() > 128) {
            throw new IllegalArgumentException("Configuracion de retencion invalida");
        }
        if (revision < retentionRevision) {
            throw new IllegalStateException("La revision de retencion no puede retroceder");
        }
        retentionRevision = revision;
        retentionFingerprint = fingerprint;
        retentionAttributedAmount = attributedAmount.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    public boolean isActive() {
        return ACTIVE.equals(status);
    }

    public boolean isPrepared() {
        return PREPARED.equals(status);
    }

    public boolean isExpiredAt(Instant now) {
        return isActive() && !leaseExpiresAt.isAfter(now);
    }

    public boolean belongsTo(UUID installationId, String terminalId, String saleId) {
        return this.installationId.equals(installationId)
                && this.terminalId.equals(terminalId)
                && this.saleId.equals(saleId);
    }

    public void renew(Instant now, Duration leaseDuration) {
        heartbeatAt = now;
        leaseExpiresAt = now.plus(leaseDuration);
    }

    public void release(Instant now) {
        if (isActive()) {
            status = RELEASED;
            closedAt = now;
        }
    }

    public void expire(Instant now) {
        if (isActive()) {
            status = EXPIRED;
            closedAt = now;
        }
    }

    public void prepare(UUID operationId, BigDecimal amount, Instant now) {
        prepareTyped(operationId, amount, BigDecimal.ZERO.setScale(2), now);
    }

    public void prepareTyped(
            UUID operationId,
            BigDecimal loyaltyAmount,
            BigDecimal returnCreditAmount,
            Instant now) {
        status = PREPARED;
        prepareOperationId = Objects.requireNonNull(operationId, "operationId");
        preparedAmount = loyaltyAmount;
        preparedLoyaltyAmount = loyaltyAmount;
        preparedReturnCreditAmount = returnCreditAmount;
        preparedAt = now;
        heartbeatAt = now;
        leaseExpiresAt = now;
    }

    public void reprepareTyped(
            UUID operationId,
            BigDecimal loyaltyAmount,
            BigDecimal returnCreditAmount,
            Instant now) {
        if (!isPrepared() || !preparedBy(operationId)) {
            throw new IllegalStateException("La preparacion central no coincide");
        }
        preparedAmount = loyaltyAmount;
        preparedLoyaltyAmount = loyaltyAmount;
        preparedReturnCreditAmount = returnCreditAmount;
        preparedAt = now;
    }

    /**
     * Reconciles the final return snapshot for the same prepared operation.
     * This does not reopen or enlarge a reservation; it only replaces the
     * retention projection after all claims have been validated atomically.
     */
    public void reconcilePreparedRetention(
            UUID operationId, String fingerprint, BigDecimal attributedAmount) {
        if (!isPrepared() || !preparedBy(operationId)) {
            throw new IllegalStateException("La preparacion central no coincide");
        }
        if (fingerprint == null || fingerprint.length() > 128) {
            throw new IllegalArgumentException("Fingerprint de retencion invalido");
        }
        retentionFingerprint = fingerprint;
        retentionAttributedAmount = attributedAmount.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    public boolean preparedBy(UUID operationId) {
        return prepareOperationId != null && prepareOperationId.equals(operationId);
    }

    public void abortPrepared(UUID operationId, Instant now) {
        if (!isPrepared() || !preparedBy(operationId)) {
            throw new IllegalStateException("La preparacion central no coincide");
        }
        status = RELEASED;
        closedAt = now;
    }

    public void finalizePrepared(UUID operationId, Instant now) {
        finalizePreparedTyped(operationId, now);
    }

    public void finalizePreparedTyped(UUID operationId, Instant now) {
        finalizePreparedTyped(operationId, now, BigDecimal.ZERO.setScale(2));
    }

    public void finalizePreparedTyped(
            UUID operationId, Instant now, BigDecimal retainedLoyaltyAmount) {
        if (!isPrepared() || !preparedBy(operationId)) {
            throw new IllegalStateException("La preparacion central no coincide");
        }
        status = CONSUMED;
        consumedLoyaltyAmount = preparedLoyaltyAmount.add(retainedLoyaltyAmount);
        // Keep the legacy alias coherent with V17: RETURN_CREDIT is exposed
        // only through its typed column and is not folded into consumed_total.
        consumedTotal = consumedLoyaltyAmount;
        consumedReturnCreditAmount = preparedReturnCreditAmount;
        closedAt = now;
    }

    private static BigDecimal nonNegativeMoney(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        BigDecimal normalized;
        try {
            normalized = value.setScale(2, java.math.RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " debe tener escala 2", exception);
        }
        if (normalized.signum() < 0) {
            throw new IllegalArgumentException(field + " no puede ser negativo");
        }
        return normalized;
    }
}
