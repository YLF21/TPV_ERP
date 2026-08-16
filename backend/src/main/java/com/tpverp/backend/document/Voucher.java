package com.tpverp.backend.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "vale", uniqueConstraints = @UniqueConstraint(
        columnNames = {"tienda_id", "codigo"}))
public class Voucher {

    @Id
    private UUID id;
    @Column(name = "tienda_id", nullable = false)
    private UUID tiendaId;
    @Column(name = "codigo", nullable = false, length = 32)
    private String code;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "familia_id", nullable = false)
    private VoucherFamily family;
    @Column(name = "importe_inicial", nullable = false, precision = 19, scale = 2)
    private BigDecimal initialAmount;
    @Column(name = "saldo", nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VoucherStatus status;
    @Column(name = "creado_en", nullable = false)
    private Instant createdAt;
    @Column(name = "caduca_el")
    private LocalDate expiresOn;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tickets_origen", nullable = false, columnDefinition = "jsonb")
    private List<String> originTickets = new ArrayList<>();
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "impresion_snapshot", columnDefinition = "jsonb")
    private String printSnapshot;
    @Version
    private long version;

    protected Voucher() {
    }

    public Voucher(
            UUID storeId, String code, BigDecimal amount,
            List<String> originTickets, Instant createdAt) {
        this(storeId, code, amount, originTickets, createdAt, null);
    }

    public Voucher(
            VoucherFamily family,
            UUID storeId, String code, BigDecimal amount,
            List<String> originTickets, Instant createdAt) {
        this(family, storeId, code, amount, originTickets, createdAt, null);
    }

    public Voucher(
            UUID storeId, String code, BigDecimal amount,
            List<String> originTickets, Instant createdAt, LocalDate expiresOn) {
        this(null, storeId, code, amount, originTickets, createdAt, expiresOn);
    }

    public Voucher(
            VoucherFamily family,
            UUID storeId, String code, BigDecimal amount,
            List<String> originTickets, Instant createdAt, LocalDate expiresOn) {
        id = UUID.randomUUID();
        this.family = family;
        tiendaId = Objects.requireNonNull(storeId, "storeId");
        this.code = required(code, "codigo");
        initialAmount = positive(amount);
        balance = initialAmount;
        this.originTickets = List.copyOf(originTickets);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresOn = expiresOn;
        status = VoucherStatus.ACTIVE;
    }

    public UUID id() {
        return id;
    }

    public UUID storeId() {
        return tiendaId;
    }

    public String code() {
        return code;
    }

    public VoucherFamily family() {
        return family;
    }

    public String familyIdentifier() {
        return family == null ? null : family.identifier();
    }

    public BigDecimal initialAmount() {
        return initialAmount;
    }

    public BigDecimal balance() {
        return balance;
    }

    public VoucherStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public LocalDate expiresOn() {
        return expiresOn;
    }

    public boolean isExpired(LocalDate today) {
        return status == VoucherStatus.ACTIVE
                && expiresOn != null
                && expiresOn.isBefore(Objects.requireNonNull(today, "today"));
    }

    public VoucherEffectiveStatus effectiveStatus(LocalDate today) {
        if (isExpired(today)) return VoucherEffectiveStatus.EXPIRED;
        return VoucherEffectiveStatus.valueOf(status.name());
    }

    public void reactivate(LocalDate newExpiresOn, LocalDate today) {
        Objects.requireNonNull(newExpiresOn, "newExpiresOn");
        Objects.requireNonNull(today, "today");
        if (!isExpired(today)) {
            throw new IllegalStateException("voucher_only_expired_can_reactivate");
        }
        if (newExpiresOn.isBefore(today)) {
            throw new IllegalArgumentException("voucher_reactivation_expiration_must_be_today_or_future");
        }
        expiresOn = newExpiresOn;
    }

    public List<String> originTickets() {
        return List.copyOf(originTickets);
    }

    public String printSnapshot() {
        return printSnapshot;
    }

    public void capturePrintSnapshot(String snapshot) {
        if (printSnapshot != null) {
            throw new IllegalStateException("voucher_print_snapshot_is_immutable");
        }
        if (snapshot == null || snapshot.isBlank()) {
            throw new IllegalArgumentException("voucher_print_snapshot_required");
        }
        printSnapshot = snapshot;
    }

    public BigDecimal consume(BigDecimal amount) {
        if (status != VoucherStatus.ACTIVE) {
            throw new IllegalStateException("vale no activo");
        }
        var normalized = positive(amount);
        var consumed = normalized.min(balance);
        balance = Money.euros(balance.subtract(consumed));
        if (balance.signum() == 0) {
            status = VoucherStatus.CONSUMED;
        }
        return consumed;
    }
    // Consume saldo sin permitir importes negativos ni dejar saldos por debajo de cero.

    public void closeForReplacement() {
        if (status != VoucherStatus.ACTIVE) {
            throw new IllegalStateException("vale no activo");
        }
        balance = Money.euros(BigDecimal.ZERO);
        status = VoucherStatus.CONSUMED;
    }
    // Cierra el vale original cuando el saldo sobrante se reemite con un codigo nuevo.

    public void restoreAfterTicketCancellation() {
        if (status == VoucherStatus.INVALIDATED) {
            throw new IllegalStateException("un vale invalidado no puede restaurarse");
        }
        balance = initialAmount;
        status = VoucherStatus.ACTIVE;
    }

    public void invalidateAfterTicketCancellation() {
        if (status == VoucherStatus.CONSUMED) {
            throw new IllegalStateException("un vale consumido no puede invalidarse");
        }
        balance = Money.euros(BigDecimal.ZERO);
        status = VoucherStatus.INVALIDATED;
    }

    private static BigDecimal positive(BigDecimal value) {
        var amount = Money.euros(value);
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("importe debe ser positivo");
        }
        return amount;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }
}
