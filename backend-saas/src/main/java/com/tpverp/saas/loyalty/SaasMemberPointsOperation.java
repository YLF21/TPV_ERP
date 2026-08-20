package com.tpverp.saas.loyalty;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "saas_member_points_operation")
public class SaasMemberPointsOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Column(name = "source_event_id", nullable = false)
    private UUID sourceEventId;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "store_sequence")
    private Long storeSequence;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 40)
    private MemberPointsOperationType operationType;

    @Column(name = "amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal amount;

    @Column(name = "source_document_id")
    private UUID sourceDocumentId;

    @Column(name = "original_document_id")
    private UUID originalDocumentId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "local_points_delta", nullable = false, precision = 19, scale = 0)
    private BigDecimal localPointsDelta;

    @Column(name = "local_debt_delta", nullable = false, precision = 19, scale = 0)
    private BigDecimal localDebtDelta;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private MemberPointsOperationStatus status;

    @Column(name = "actual_points_delta", precision = 19, scale = 0)
    private BigDecimal actualPointsDelta;

    @Column(name = "actual_debt_delta", precision = 19, scale = 0)
    private BigDecimal actualDebtDelta;

    @Column(name = "points_before", precision = 19, scale = 0)
    private BigDecimal pointsBefore;

    @Column(name = "points_after", precision = 19, scale = 0)
    private BigDecimal pointsAfter;

    @Column(name = "debt_before", precision = 19, scale = 0)
    private BigDecimal debtBefore;

    @Column(name = "debt_after", precision = 19, scale = 0)
    private BigDecimal debtAfter;

    @Column(name = "resolution_error", length = 1000)
    private String resolutionError;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected SaasMemberPointsOperation() {
    }

    public SaasMemberPointsOperation(
            UUID companyId,
            UUID operationId,
            UUID sourceEventId,
            UUID storeId,
            Long storeSequence,
            int schemaVersion,
            UUID memberId,
            MemberPointsOperationType operationType,
            BigDecimal amount,
            UUID sourceDocumentId,
            UUID originalDocumentId,
            Instant occurredAt,
            BigDecimal localPointsDelta,
            BigDecimal localDebtDelta,
            String payloadHash,
            Instant receivedAt
    ) {
        this.companyId = companyId;
        this.operationId = operationId;
        this.sourceEventId = sourceEventId;
        this.storeId = Objects.requireNonNull(storeId, "storeId autenticado es obligatorio");
        this.storeSequence = storeSequence;
        this.schemaVersion = schemaVersion;
        this.memberId = memberId;
        this.operationType = operationType;
        this.amount = amount;
        this.sourceDocumentId = sourceDocumentId;
        this.originalDocumentId = originalDocumentId;
        this.occurredAt = occurredAt;
        this.localPointsDelta = localPointsDelta;
        this.localDebtDelta = localDebtDelta;
        this.payloadHash = payloadHash;
        this.status = MemberPointsOperationStatus.PENDING_BOOTSTRAP;
        this.receivedAt = receivedAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getOperationId() {
        return operationId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public Long getStoreSequence() {
        return storeSequence;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public UUID getMemberId() {
        return memberId;
    }

    public MemberPointsOperationType getOperationType() {
        return operationType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public UUID getSourceDocumentId() {
        return sourceDocumentId;
    }

    public UUID getOriginalDocumentId() {
        return originalDocumentId;
    }

    public MemberPointsOperationStatus getStatus() {
        return status;
    }

    public boolean hasPayloadHash(String candidate) {
        return Objects.equals(payloadHash, candidate);
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public void markAbsorbedBootstrap(Instant now) {
        status = MemberPointsOperationStatus.ABSORBED_BOOTSTRAP;
        actualPointsDelta = BigDecimal.ZERO;
        actualDebtDelta = BigDecimal.ZERO;
        resolutionError = null;
        resolvedAt = now;
    }

    public void markPendingDependency(String reason, BigDecimal points, BigDecimal debt) {
        status = MemberPointsOperationStatus.PENDING_DEPENDENCY;
        actualPointsDelta = BigDecimal.ZERO;
        actualDebtDelta = BigDecimal.ZERO;
        pointsBefore = points;
        pointsAfter = points;
        debtBefore = debt;
        debtAfter = debt;
        resolutionError = abbreviate(reason);
        resolvedAt = null;
    }

    public void markApplied(
            BigDecimal beforePoints,
            BigDecimal afterPoints,
            BigDecimal beforeDebt,
            BigDecimal afterDebt,
            Instant now
    ) {
        status = MemberPointsOperationStatus.APPLIED;
        pointsBefore = beforePoints;
        pointsAfter = afterPoints;
        debtBefore = beforeDebt;
        debtAfter = afterDebt;
        actualPointsDelta = afterPoints.subtract(beforePoints);
        actualDebtDelta = afterDebt.subtract(beforeDebt);
        resolutionError = null;
        resolvedAt = now;
    }

    public void markConflict(String reason, BigDecimal points, BigDecimal debt, Instant now) {
        status = MemberPointsOperationStatus.CONFLICT;
        actualPointsDelta = BigDecimal.ZERO;
        actualDebtDelta = BigDecimal.ZERO;
        pointsBefore = points;
        pointsAfter = points;
        debtBefore = debt;
        debtAfter = debt;
        resolutionError = abbreviate(reason);
        resolvedAt = now;
    }

    public void markConflictWithoutState(String reason, Instant now) {
        status = MemberPointsOperationStatus.CONFLICT;
        actualPointsDelta = BigDecimal.ZERO;
        actualDebtDelta = BigDecimal.ZERO;
        resolutionError = abbreviate(reason);
        resolvedAt = now;
    }

    private String abbreviate(String value) {
        if (value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 1000);
    }
}
