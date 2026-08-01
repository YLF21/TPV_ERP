package com.tpverp.backend.cash;

import com.tpverp.backend.document.Money;
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
@Table(name = "operacion_cierre_caja")
public class CashCloseOperation {

    @Id
    private UUID id;

    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;

    @Column(name = "terminal_id", nullable = false)
    private UUID terminalId;

    @Column(name = "sesion_caja_id", nullable = false, unique = true)
    private UUID sessionId;

    @Column(name = "movimiento_retirada_id", unique = true)
    private UUID withdrawalMovementId;

    @Column(name = "importe_retirada", nullable = false, precision = 19, scale = 2)
    private BigDecimal withdrawalAmount;

    @Column(name = "comentario_retirada", length = 500)
    private String withdrawalComment;

    @Column(name = "huella_retirada", nullable = false, length = 64)
    private String withdrawalHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 32)
    private CashCloseOperationStatus status;

    @Column(name = "creado_en", nullable = false)
    private Instant createdAt;

    @Column(name = "actualizado_en", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected CashCloseOperation() {
    }

    public static CashCloseOperation start(
            UUID id,
            UUID storeId,
            UUID terminalId,
            UUID sessionId,
            UUID withdrawalMovementId,
            BigDecimal withdrawalAmount,
            String withdrawalComment,
            String withdrawalHash,
            Instant createdAt) {
        var operation = new CashCloseOperation();
        operation.id = Objects.requireNonNull(id, "id");
        operation.storeId = Objects.requireNonNull(storeId, "storeId");
        operation.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        operation.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        operation.withdrawalMovementId = withdrawalMovementId;
        operation.withdrawalAmount = nonNegative(withdrawalAmount);
        operation.withdrawalComment = normalizeComment(withdrawalComment);
        operation.withdrawalHash = validHash(withdrawalHash);
        operation.status = CashCloseOperationStatus.INICIADA;
        operation.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        operation.updatedAt = createdAt;
        return operation;
    }

    public boolean matches(UUID expectedStoreId, UUID expectedTerminalId, UUID expectedSessionId) {
        return storeId.equals(expectedStoreId)
                && terminalId.equals(expectedTerminalId)
                && sessionId.equals(expectedSessionId);
    }

    public boolean matchesWithdrawal(String expectedHash, BigDecimal expectedAmount) {
        return withdrawalHash.equals(expectedHash)
                && withdrawalAmount.compareTo(Money.euros(expectedAmount)) == 0;
    }

    public void recordAttempt(boolean closedSession, Instant at) {
        status = closedSession
                ? CashCloseOperationStatus.CERRADA
                : CashCloseOperationStatus.REQUIERE_ARQUEO;
        updatedAt = Objects.requireNonNull(at, "at");
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getTerminalId() {
        return terminalId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getWithdrawalMovementId() {
        return withdrawalMovementId;
    }

    public BigDecimal getWithdrawalAmount() {
        return withdrawalAmount;
    }

    public String getWithdrawalComment() {
        return withdrawalComment;
    }

    public CashCloseOperationStatus getStatus() {
        return status;
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        var amount = Money.euros(value);
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("withdrawalAmount no puede ser negativo");
        }
        return amount;
    }

    private static String normalizeComment(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String validHash(String value) {
        var hash = Objects.requireNonNull(value, "withdrawalHash");
        if (!hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("withdrawalHash debe ser SHA-256 hexadecimal");
        }
        return hash;
    }
}
