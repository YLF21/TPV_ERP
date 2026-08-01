package com.tpverp.backend.cash;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "idempotencia_retirada_cierre")
public class CashCloseWithdrawalIdempotency {

    @Id
    @Column(name = "clave_idempotencia", nullable = false)
    private UUID idempotencyKey;

    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;

    @Column(name = "terminal_id", nullable = false)
    private UUID terminalId;

    @Column(name = "sesion_caja_id", nullable = false)
    private UUID sessionId;

    @Column(name = "movimiento_caja_id", unique = true)
    private UUID movementId;

    @Column(name = "huella_solicitud", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "creado_en", nullable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected CashCloseWithdrawalIdempotency() {
    }

    private CashCloseWithdrawalIdempotency(
            UUID idempotencyKey,
            UUID storeId,
            UUID terminalId,
            UUID sessionId,
            UUID movementId,
            String requestHash,
            Instant createdAt) {
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.movementId = movementId;
        this.requestHash = validHash(requestHash);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static CashCloseWithdrawalIdempotency reserve(
            UUID idempotencyKey,
            UUID storeId,
            UUID terminalId,
            UUID sessionId,
            UUID movementId,
            String requestHash,
            Instant createdAt) {
        return new CashCloseWithdrawalIdempotency(
                idempotencyKey,
                storeId,
                terminalId,
                sessionId,
                movementId,
                requestHash,
                createdAt);
    }

    public boolean matches(
            UUID expectedStoreId,
            UUID expectedTerminalId,
            UUID expectedSessionId,
            String expectedRequestHash) {
        return storeId.equals(expectedStoreId)
                && terminalId.equals(expectedTerminalId)
                && sessionId.equals(expectedSessionId)
                && requestHash.equals(expectedRequestHash);
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getMovementId() {
        return movementId;
    }

    private static String validHash(String value) {
        var hash = Objects.requireNonNull(value, "requestHash");
        if (!hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash debe ser SHA-256 hexadecimal");
        }
        return hash;
    }
}
