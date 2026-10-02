package com.tpverp.backend.cash;

import com.tpverp.backend.document.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "sesion_caja")
public class CashSession {

    @Id
    private UUID id;

    @Column(name = "tienda_id", nullable = false)
    private UUID tiendaId;

    @Column(name = "terminal_id", nullable = false)
    private UUID terminalId;

    @Column(name = "usuario_apertura_id", nullable = false)
    private UUID openingUserId;

    @Column(name = "abierta_en", nullable = false)
    private Instant openedAt;

    @Column(name = "fondo_inicial", nullable = false, precision = 19, scale = 2)
    private BigDecimal openingFund;

    @Column(name = "usuario_cierre_id")
    private UUID closingUserId;

    @Column(name = "cerrada_en")
    private Instant closedAt;

    @Column(name = "efectivo_teorico", precision = 19, scale = 2)
    private BigDecimal expectedCash;

    @Column(name = "fondo_dejado", precision = 19, scale = 2)
    private BigDecimal retainedFund;

    @Column(name = "descuadre", precision = 19, scale = 2)
    private BigDecimal discrepancy;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 16)
    private CashSessionStatus status;

    @Column(name = "cierre_tardio", nullable = false)
    private boolean lateClosing;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CashReconciliationAttempt> attempts = new ArrayList<>();

    @Column(name = "apertura_esperado", precision = 19, scale = 2)
    private BigDecimal openingExpectedFund;

    @Column(name = "apertura_contado", precision = 19, scale = 2)
    private BigDecimal openingCountedFund;

    @Column(name = "apertura_diferencia", precision = 19, scale = 2)
    private BigDecimal openingDifference;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "apertura_desglose", columnDefinition = "jsonb")
    private List<CashDenominationCommand> openingDenominations;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "fondo_dejado_desglose", columnDefinition = "jsonb")
    private List<CashDenominationCommand> retainedFundDenominations;

    public void recordOpeningCount(BigDecimal expected, List<CashDenominationCommand> denominations) {
        if (openingCountedFund != null) throw new IllegalStateException("El recuento de apertura ya existe");
        openingExpectedFund = Money.euros(expected);
        openingCountedFund = openingFund;
        openingDifference = Money.euros(openingCountedFund.subtract(openingExpectedFund));
        openingDenominations = denominations == null ? List.of() : List.copyOf(denominations);
    }

    public BigDecimal getOpeningExpectedFund() { return openingExpectedFund; }
    public BigDecimal getOpeningCountedFund() { return openingCountedFund; }
    public BigDecimal getOpeningDifference() { return openingDifference; }
    public List<CashDenominationCommand> getOpeningDenominations() { return openingDenominations; }
    public List<CashDenominationCommand> getRetainedFundDenominations() { return retainedFundDenominations; }
    public void recordRetainedFundDenominations(List<CashDenominationCommand> denominations) {
        retainedFundDenominations = denominations == null ? List.of() : List.copyOf(denominations);
    }

    @Version
    private long version;

    protected CashSession() {
    }

    // Abre una sesion de caja con importes normalizados al formato monetario del sistema.
    public static CashSession open(
            UUID storeId,
            UUID terminalId,
            UUID userId,
            Instant openedAt,
            BigDecimal openingFund) {
        var session = new CashSession();
        session.id = UUID.randomUUID();
        session.tiendaId = Objects.requireNonNull(storeId, "storeId");
        session.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        session.openingUserId = Objects.requireNonNull(userId, "userId");
        session.openedAt = Objects.requireNonNull(openedAt, "openedAt");
        session.openingFund = nonNegative(openingFund, "openingFund");
        session.status = CashSessionStatus.ABIERTA;
        return session;
    }

    // Records a cash-count attempt and closes the session according to tolerance and attempt number.
    public CashReconciliationAttempt registerAttempt(
            UUID userId,
            Instant at,
            BigDecimal declaredFund,
            BigDecimal expectedCash,
            BigDecimal tolerance) {
        return registerAttempt(
                userId,
                at,
                declaredFund,
                expectedCash,
                tolerance,
                null,
                null,
                null);
    }

    public CashReconciliationAttempt registerAttempt(
            UUID userId,
            Instant at,
            BigDecimal declaredFund,
            BigDecimal expectedCash,
            BigDecimal tolerance,
            CashCloseOperation closeOperation,
            UUID idempotencyKey,
            String requestHash) {
        requireOpen();
        if (attempts.size() >= 2) {
            throw new IllegalStateException("solo se permiten dos intentos de arqueo");
        }
        var declared = nonNegative(declaredFund, "declaredFund");
        var expected = Money.euros(expectedCash);
        var diff = Money.euros(declared.subtract(expected));
        var normalizedTolerance = nonNegative(tolerance, "tolerance");
        var attemptNumber = attempts.size() + 1;
        var closesSession = attemptNumber == 2
                || diff.abs().compareTo(normalizedTolerance) <= 0;
        var attempt = new CashReconciliationAttempt(
                this,
                attemptNumber,
                userId,
                at,
                declared,
                expected,
                diff,
                closesSession,
                closeOperation,
                idempotencyKey,
                requestHash);
        attempts.add(attempt);
        if (closesSession) {
            close(userId, at, expected, declared, diff);
        }
        return attempt;
    }

    // Cierra manualmente la sesion guardando efectivo teorico, fondo dejado y descuadre.
    public void close(
            UUID userId,
            Instant at,
            BigDecimal expectedCash,
            BigDecimal retainedFund,
            BigDecimal discrepancy) {
        requireOpen();
        this.closingUserId = Objects.requireNonNull(userId, "userId");
        this.closedAt = Objects.requireNonNull(at, "at");
        this.expectedCash = Money.euros(expectedCash);
        this.retainedFund = nonNegative(retainedFund, "retainedFund");
        this.discrepancy = Money.euros(discrepancy);
        this.status = CashSessionStatus.CERRADA;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return tiendaId;
    }

    public UUID getTerminalId() {
        return terminalId;
    }

    public CashSessionStatus getStatus() {
        return status;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public BigDecimal getOpeningFund() {
        return openingFund;
    }

    public BigDecimal getExpectedCash() {
        return expectedCash;
    }

    public BigDecimal getRetainedFund() {
        return retainedFund;
    }

    public BigDecimal getDiscrepancy() {
        return discrepancy;
    }

    public UUID getClosingUserId() {
        return closingUserId;
    }

    public boolean isLateClosing() {
        return lateClosing;
    }

    public List<CashReconciliationAttempt> getAttempts() {
        return List.copyOf(attempts);
    }

    void markLateClosing() {
        lateClosing = true;
    }

    private void requireOpen() {
        if (status != CashSessionStatus.ABIERTA) {
            throw new IllegalStateException("la sesion de caja ya esta cerrada");
        }
    }

    private static BigDecimal nonNegative(BigDecimal value, String field) {
        var amount = Money.euros(value);
        if (amount.signum() < 0) {
            throw new IllegalArgumentException(field + " no puede ser negativo");
        }
        return amount;
    }
}
