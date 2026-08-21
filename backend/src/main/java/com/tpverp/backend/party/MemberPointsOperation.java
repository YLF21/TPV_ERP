package com.tpverp.backend.party;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "member_points_operation")
public class MemberPointsOperation {

    @Id
    @Column(name = "operation_id")
    private UUID operationId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "miembro_id", nullable = false)
    private Member member;
    @Column(name = "empresa_id", nullable = false)
    private UUID companyId;
    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;
    @Column(name = "store_sequence", nullable = false)
    private long storeSequence;
    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 32)
    private MemberPointsOperationType operationType;
    @Column(nullable = false)
    private long amount;
    @Column(name = "source_document_id")
    private UUID sourceDocumentId;
    @Column(name = "original_document_id")
    private UUID originalDocumentId;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    @Column(name = "local_points_delta", nullable = false)
    private long localPointsDelta;
    @Column(name = "local_debt_delta", nullable = false)
    private long localDebtDelta;
    @Column(name = "source_checkpoint", length = 64)
    private String sourceCheckpoint;
    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    protected MemberPointsOperation() {
    }

    public MemberPointsOperation(
            UUID operationId,
            Member member,
            UUID companyId,
            UUID storeId,
            long storeSequence,
            MemberPointsOperationType operationType,
            long amount,
            UUID sourceDocumentId,
            UUID originalDocumentId,
            Instant occurredAt,
            long localPointsDelta,
            long localDebtDelta,
            String sourceCheckpoint) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.member = Objects.requireNonNull(member, "member");
        this.companyId = Objects.requireNonNull(companyId, "companyId");
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        if (storeSequence <= 0) {
            throw new IllegalArgumentException("storeSequence debe ser positiva");
        }
        this.storeSequence = storeSequence;
        this.operationType = Objects.requireNonNull(operationType, "operationType");
        validateAmount(operationType, amount);
        validateDocuments(operationType, sourceDocumentId, originalDocumentId);
        this.amount = amount;
        this.sourceDocumentId = sourceDocumentId;
        this.originalDocumentId = originalDocumentId;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.localPointsDelta = localPointsDelta;
        this.localDebtDelta = localDebtDelta;
        this.sourceCheckpoint = normalizeCheckpoint(operationType, sourceCheckpoint);
        this.payloadHash = calculatePayloadHash();
    }

    private static void validateAmount(MemberPointsOperationType type, long amount) {
        if (type == MemberPointsOperationType.MANUAL_ADJUSTMENT) {
            if (amount == 0) {
                throw new IllegalArgumentException("El ajuste manual de puntos no puede ser cero");
            }
            return;
        }
        if (amount < 0) {
            throw new IllegalArgumentException("La cantidad de puntos no puede ser negativa");
        }
    }

    private static void validateDocuments(
            MemberPointsOperationType type,
            UUID sourceDocumentId,
            UUID originalDocumentId) {
        switch (type) {
            case SALE_EARN -> {
                requireDocument(sourceDocumentId, "sourceDocumentId");
                requireAbsent(originalDocumentId, "originalDocumentId");
            }
            case RETURN_REVERSAL, RETURN_CANCELLATION -> {
                requireDocument(sourceDocumentId, "sourceDocumentId");
                requireDocument(originalDocumentId, "originalDocumentId");
                if (sourceDocumentId.equals(originalDocumentId)) {
                    throw new IllegalArgumentException(
                            "Los documentos de origen y devolucion deben ser distintos");
                }
            }
            case SALE_CANCELLATION -> {
                requireAbsent(sourceDocumentId, "sourceDocumentId");
                requireDocument(originalDocumentId, "originalDocumentId");
            }
            case MANUAL_ADJUSTMENT -> {
                requireAbsent(sourceDocumentId, "sourceDocumentId");
                requireAbsent(originalDocumentId, "originalDocumentId");
            }
        }
    }

    private static void requireDocument(UUID value, String field) {
        Objects.requireNonNull(value, field);
    }

    private static void requireAbsent(UUID value, String field) {
        if (value != null) {
            throw new IllegalArgumentException(field + " no corresponde al tipo de operacion");
        }
    }

    private static String normalizeCheckpoint(
            MemberPointsOperationType type, String sourceCheckpoint) {
        if (type != MemberPointsOperationType.SALE_EARN) {
            if (sourceCheckpoint != null) {
                throw new IllegalArgumentException(
                        "sourceCheckpoint solo corresponde a SALE_EARN");
            }
            return null;
        }
        if (sourceCheckpoint == null || sourceCheckpoint.isBlank()) {
            throw new IllegalArgumentException("sourceCheckpoint es obligatorio para SALE_EARN");
        }
        var value = sourceCheckpoint.trim();
        if (value.length() > 64) {
            throw new IllegalArgumentException("sourceCheckpoint excede 64 caracteres");
        }
        return value;
    }

    private String calculatePayloadHash() {
        var canonical = String.join("\n",
                "schemaVersion=1",
                "operationId=" + operationId,
                "memberId=" + member.getId(),
                "operationType=" + operationType.name(),
                "amount=" + amount,
                "sourceDocumentId=" + nullable(sourceDocumentId),
                "originalDocumentId=" + nullable(originalDocumentId),
                "occurredAt=" + occurredAt,
                "localPointsDelta=" + localPointsDelta,
                "localDebtDelta=" + localDebtDelta);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    private static String nullable(UUID value) {
        return value == null ? "-" : value.toString();
    }

    public UUID getOperationId() {
        return operationId;
    }

    public Member getMember() {
        return member;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public long getStoreSequence() {
        return storeSequence;
    }

    public MemberPointsOperationType getOperationType() {
        return operationType;
    }

    public long getAmount() {
        return amount;
    }

    public UUID getSourceDocumentId() {
        return sourceDocumentId;
    }

    public UUID getOriginalDocumentId() {
        return originalDocumentId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public long getLocalPointsDelta() {
        return localPointsDelta;
    }

    public long getLocalDebtDelta() {
        return localDebtDelta;
    }

    public String getSourceCheckpoint() {
        return sourceCheckpoint;
    }

    public String getPayloadHash() {
        return payloadHash;
    }
}
