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
@Table(name = "movimiento_caja")
public class CashMovement {

    @Id
    private UUID id;

    @Column(name = "tienda_id", nullable = false)
    private UUID tiendaId;

    @Column(name = "terminal_id", nullable = false)
    private UUID terminalId;

    @Column(name = "sesion_caja_id")
    private UUID sesionCajaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 32)
    private CashMovementType type;

    @Column(name = "importe", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    @Column(name = "usuario_id", nullable = false)
    private UUID userId;

    @Column(name = "usuario_autorizador_id")
    private UUID authorizerUserId;

    @Column(name = "comentario", length = 500)
    private String comment;

    @Column(name = "documento_id")
    private UUID documentId;

    @Column(name = "documento_pago_id")
    private UUID documentoPagoId;

    @Column(name = "impreso_en")
    private Instant printedAt;

    @OneToMany(mappedBy = "movement", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CashMovementDenomination> denominations = new ArrayList<>();

    @Version
    private long version;

    protected CashMovement() {
    }

    private CashMovement(
            UUID storeId,
            UUID terminalId,
            UUID sessionId,
            CashMovementType type,
            BigDecimal amount,
            Instant createdAt,
            UUID userId,
            UUID authorizerUserId,
            String comment,
            UUID documentId,
            UUID paymentId) {
        this.id = UUID.randomUUID();
        this.tiendaId = Objects.requireNonNull(storeId, "storeId");
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        this.sesionCajaId = sessionId;
        this.type = Objects.requireNonNull(type, "type");
        this.amount = positive(amount);
        this.creadoEn = Objects.requireNonNull(createdAt, "createdAt");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.authorizerUserId = authorizerUserId;
        this.comment = optional(comment);
        this.documentId = documentId;
        this.documentoPagoId = paymentId;
    }

    // Crea un movimiento asociado a una sesion de caja abierta.
    public static CashMovement sessionMovement(
            UUID storeId,
            UUID terminalId,
            CashSession session,
            CashMovementType type,
            BigDecimal amount,
            Instant createdAt,
            UUID userId,
            UUID authorizerUserId,
            String comment,
            UUID documentId,
            UUID paymentId) {
        Objects.requireNonNull(session, "session");
        return sessionMovement(
                session.getStoreId(), session.getTerminalId(), session.getId(), type, amount, createdAt,
                userId, authorizerUserId, comment, documentId, paymentId);
    }

    // Crea un movimiento asociado a una sesion de caja abierta cuando solo se conoce su id.
    public static CashMovement sessionMovement(
            UUID storeId,
            UUID terminalId,
            UUID sessionId,
            CashMovementType type,
            BigDecimal amount,
            Instant createdAt,
            UUID userId,
            UUID authorizerUserId,
            String comment,
            UUID documentId,
            UUID paymentId) {
        return new CashMovement(
                storeId, terminalId, Objects.requireNonNull(sessionId, "sessionId"), type,
                amount, createdAt, userId, authorizerUserId, comment, documentId, paymentId);
    }

    // Crea una entrada pendiente de asociar a una sesion, usada para traspasos entre sesiones.
    public static CashMovement betweenSessions(
            UUID storeId,
            UUID terminalId,
            BigDecimal amount,
            Instant createdAt,
            UUID userId,
            UUID authorizerUserId,
            String comment) {
        return betweenSessionEntry(storeId, terminalId, amount, createdAt, userId, authorizerUserId, comment);
    }

    public static CashMovement betweenSessionEntry(
            UUID storeId,
            UUID terminalId,
            BigDecimal amount,
            Instant createdAt,
            UUID userId,
            UUID authorizerUserId,
            String comment) {
        return betweenSessionMovement(
                storeId, terminalId, CashMovementType.ENTRADA_ENTRE_SESIONES,
                amount, createdAt, userId, authorizerUserId, comment);
    }

    public static CashMovement betweenSessionWithdrawal(
            UUID storeId,
            UUID terminalId,
            BigDecimal amount,
            Instant createdAt,
            UUID userId,
            UUID authorizerUserId,
            String comment) {
        return betweenSessionMovement(
                storeId, terminalId, CashMovementType.RETIRADA_ENTRE_SESIONES,
                amount, createdAt, userId, authorizerUserId, comment);
    }

    private static CashMovement betweenSessionMovement(
            UUID storeId,
            UUID terminalId,
            CashMovementType type,
            BigDecimal amount,
            Instant createdAt,
            UUID userId,
            UUID authorizerUserId,
            String comment) {
        return new CashMovement(
                storeId, terminalId, null, type,
                amount, createdAt, userId, authorizerUserId, comment, null, null);
    }

    public void addDenomination(BigDecimal denomination, int quantity) {
        denominations.add(new CashMovementDenomination(this, denomination, quantity));
    }

    // Suma el efectivo declarado por denominaciones normalizando el resultado a euros.
    public BigDecimal totalDenominations() {
        return denominations.stream()
                .map(CashMovementDenomination::subtotal)
                .reduce(Money.euros("0"), BigDecimal::add)
                .setScale(Money.SCALE, Money.ROUNDING);
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

    public UUID getSessionId() {
        return sesionCajaId;
    }

    public CashMovementType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Instant getCreatedAt() {
        return creadoEn;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getAuthorizerUserId() {
        return authorizerUserId;
    }

    public String getComment() {
        return comment;
    }

    public List<CashMovementDenomination> getDenominations() {
        return List.copyOf(denominations);
    }

    private static BigDecimal positive(BigDecimal value) {
        var amount = Money.euros(value);
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("importe debe ser positivo");
        }
        return amount;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
