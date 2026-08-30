package com.tpverp.saas.loyalty;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "saas_member_balance_retention_receipt")
public class SaasMemberBalanceRetentionReceipt {

    @Id
    @Column(name = "operation_id")
    private UUID operationId;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Column(name = "source_document_id", nullable = false)
    private UUID sourceDocumentId;

    @Column(name = "return_document_id")
    private UUID returnDocumentId;

    @Column(name = "attributed_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal attributedAmount;

    @Column(nullable = false, length = 128)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SaasMemberBalanceRetentionReceiptStatus status;

    @Column(name = "recovered_known", nullable = false, precision = 19, scale = 2)
    private BigDecimal recoveredKnown;

    @Column(name = "pending_missing", nullable = false, precision = 19, scale = 2)
    private BigDecimal pendingMissing;

    @Column(name = "spent_shortfall", nullable = false, precision = 19, scale = 2)
    private BigDecimal spentShortfall;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected SaasMemberBalanceRetentionReceipt() {
    }

    public SaasMemberBalanceRetentionReceipt(
            UUID operationId,
            UUID companyId,
            UUID storeId,
            UUID memberId,
            UUID sourceDocumentId,
            UUID returnDocumentId,
            BigDecimal attributedAmount,
            String fingerprint,
            BigDecimal recoveredKnown,
            BigDecimal pendingMissing,
            BigDecimal spentShortfall,
            Instant now) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.memberId = Objects.requireNonNull(memberId, "memberId");
        this.sourceDocumentId = Objects.requireNonNull(sourceDocumentId, "sourceDocumentId");
        this.returnDocumentId = returnDocumentId;
        this.attributedAmount = money(attributedAmount, "attributedAmount");
        this.fingerprint = requireFingerprint(fingerprint);
        this.recoveredKnown = nonNegative(recoveredKnown, "recoveredKnown");
        this.pendingMissing = nonNegative(pendingMissing, "pendingMissing");
        this.spentShortfall = nonNegative(spentShortfall, "spentShortfall");
        this.status = SaasMemberBalanceRetentionReceiptStatus.COMMITTED;
        validateMetrics(this.attributedAmount, this.recoveredKnown,
                this.pendingMissing, this.spentShortfall);
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public UUID getOperationId() { return operationId; }
    public UUID getCompanyId() { return companyId; }
    public UUID getStoreId() { return storeId; }
    public UUID getMemberId() { return memberId; }
    public UUID getSourceDocumentId() { return sourceDocumentId; }
    public UUID getReturnDocumentId() { return returnDocumentId; }
    public BigDecimal getAttributedAmount() { return attributedAmount; }
    public String getFingerprint() { return fingerprint; }
    public SaasMemberBalanceRetentionReceiptStatus getStatus() { return status; }
    public BigDecimal getRecoveredKnown() { return recoveredKnown; }
    public BigDecimal getPendingMissing() { return pendingMissing; }
    public BigDecimal getSpentShortfall() { return spentShortfall; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void replaceMetrics(
            BigDecimal recoveredKnown, BigDecimal pendingMissing,
            BigDecimal spentShortfall, Instant now) {
        BigDecimal nextRecoveredKnown = nonNegative(recoveredKnown, "recoveredKnown");
        BigDecimal nextPendingMissing = nonNegative(pendingMissing, "pendingMissing");
        BigDecimal nextSpentShortfall = nonNegative(spentShortfall, "spentShortfall");
        validateMetrics(this.attributedAmount, nextRecoveredKnown,
                nextPendingMissing, nextSpentShortfall);
        this.recoveredKnown = nextRecoveredKnown;
        this.pendingMissing = nextPendingMissing;
        this.spentShortfall = nextSpentShortfall;
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public boolean matchesImmutable(
            UUID companyId, UUID storeId, UUID memberId, UUID sourceDocumentId,
            BigDecimal attributedAmount, String fingerprint) {
        return matchesImmutable(companyId, storeId, memberId, sourceDocumentId, null,
                attributedAmount, fingerprint);
    }

    public boolean matchesImmutable(
            UUID companyId, UUID storeId, UUID memberId, UUID sourceDocumentId,
            UUID requestedReturnDocumentId, BigDecimal attributedAmount, String fingerprint) {
        return this.companyId.equals(companyId)
                && this.storeId.equals(storeId)
                && this.memberId.equals(memberId)
                && this.sourceDocumentId.equals(sourceDocumentId)
                && (requestedReturnDocumentId == null
                        ? this.returnDocumentId == null
                        : this.returnDocumentId == null
                                || this.returnDocumentId.equals(requestedReturnDocumentId))
                && this.attributedAmount.compareTo(attributedAmount) == 0
                && this.fingerprint.equals(fingerprint);
    }

    public void attachReturnDocument(UUID returnDocumentId, Instant now) {
        if (returnDocumentId == null) return;
        if (this.returnDocumentId != null && !this.returnDocumentId.equals(returnDocumentId)) {
            throw new IllegalArgumentException("returnDocumentId inmutable del receipt");
        }
        this.returnDocumentId = returnDocumentId;
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    private static BigDecimal money(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        try {
            return value.setScale(2, java.math.RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " debe tener escala 2", exception);
        }
    }

    private static BigDecimal nonNegative(BigDecimal value, String field) {
        BigDecimal normalized = money(value, field);
        if (normalized.signum() < 0) throw new IllegalArgumentException(field + " no puede ser negativo");
        return normalized;
    }

    private static void validateMetrics(
            BigDecimal attributedAmount, BigDecimal recoveredKnown,
            BigDecimal pendingMissing, BigDecimal spentShortfall) {
        if (recoveredKnown.add(pendingMissing).add(spentShortfall)
                .compareTo(attributedAmount) != 0) {
            throw new IllegalArgumentException(
                    "Las metricas del receipt deben cuadrar con attributedAmount");
        }
    }

    private static String requireFingerprint(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("fingerprint debe ser SHA-256 hexadecimal");
        }
        return value;
    }
}
