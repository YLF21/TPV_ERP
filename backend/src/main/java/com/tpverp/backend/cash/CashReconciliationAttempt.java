package com.tpverp.backend.cash;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "intento_arqueo_caja")
public class CashReconciliationAttempt {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sesion_caja_id", nullable = false)
    private CashSession session;

    @Column(name = "numero_intento", nullable = false)
    private int attemptNumber;

    @Column(name = "usuario_id", nullable = false)
    private UUID userId;

    @Column(name = "creado_en", nullable = false)
    private Instant createdAt;

    @Column(name = "fondo_declarado", nullable = false, precision = 19, scale = 2)
    private BigDecimal declaredFund;

    @Column(name = "efectivo_teorico", nullable = false, precision = 19, scale = 2)
    private BigDecimal expectedCash;

    @Column(name = "descuadre", nullable = false, precision = 19, scale = 2)
    private BigDecimal discrepancy;

    @Column(name = "cerro_sesion", nullable = false)
    private boolean closedSession;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operacion_cierre_id")
    private CashCloseOperation closeOperation;

    @Column(name = "clave_idempotencia")
    private UUID idempotencyKey;

    @Column(name = "huella_solicitud", length = 64)
    private String requestHash;

    protected CashReconciliationAttempt() {
    }

    CashReconciliationAttempt(
            CashSession session,
            int attemptNumber,
            UUID userId,
            Instant createdAt,
            BigDecimal declaredFund,
            BigDecimal expectedCash,
            BigDecimal discrepancy,
            boolean closedSession) {
        this(
                session,
                attemptNumber,
                userId,
                createdAt,
                declaredFund,
                expectedCash,
                discrepancy,
                closedSession,
                null,
                null,
                null);
    }

    CashReconciliationAttempt(
            CashSession session,
            int attemptNumber,
            UUID userId,
            Instant createdAt,
            BigDecimal declaredFund,
            BigDecimal expectedCash,
            BigDecimal discrepancy,
            boolean closedSession,
            CashCloseOperation closeOperation,
            UUID idempotencyKey,
            String requestHash) {
        this.id = UUID.randomUUID();
        this.session = Objects.requireNonNull(session, "session");
        this.attemptNumber = attemptNumber;
        this.userId = Objects.requireNonNull(userId, "userId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.declaredFund = Objects.requireNonNull(declaredFund, "declaredFund");
        this.expectedCash = Objects.requireNonNull(expectedCash, "expectedCash");
        this.discrepancy = Objects.requireNonNull(discrepancy, "discrepancy");
        this.closedSession = closedSession;
        this.closeOperation = closeOperation;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash == null ? null : validHash(requestHash);
        if ((closeOperation == null) != (idempotencyKey == null)
                || (closeOperation == null) != (requestHash == null)) {
            throw new IllegalArgumentException("La identidad durable del arqueo debe estar completa");
        }
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public BigDecimal getDeclaredFund() {
        return declaredFund;
    }

    public BigDecimal getDiscrepancy() {
        return discrepancy;
    }

    public BigDecimal getExpectedCash() {
        return expectedCash;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public boolean matches(CashCloseOperation operation, String expectedRequestHash) {
        return closeOperation != null
                && closeOperation.getId().equals(operation.getId())
                && requestHash.equals(expectedRequestHash);
    }

    public boolean closedSession() {
        return closedSession;
    }

    private static String validHash(String value) {
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash debe ser SHA-256 hexadecimal");
        }
        return value;
    }
}
