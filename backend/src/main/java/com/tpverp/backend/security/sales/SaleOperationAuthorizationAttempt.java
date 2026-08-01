package com.tpverp.backend.security.sales;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "intento_autorizacion_operacion_venta")
public class SaleOperationAuthorizationAttempt {

    @Id
    private UUID id;

    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;

    @Column(name = "operador_id", nullable = false)
    private UUID operatorId;

    @Column(name = "terminal_id", nullable = false)
    private UUID terminalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "codigo_operacion", nullable = false, length = 64)
    private SaleOperationCode operationCode;

    @Column(name = "fallos_consecutivos", nullable = false)
    private int consecutiveFailures;

    @Column(name = "bloqueado_hasta")
    private Instant blockedUntil;

    @Column(name = "reserva_id")
    private UUID reservationId;

    @Column(name = "reserva_hasta")
    private Instant reservationUntil;

    @Column(name = "ultimo_fallo_en")
    private Instant lastFailureAt;

    @Column(name = "actualizada_en", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected SaleOperationAuthorizationAttempt() {
    }

    public boolean isBlockedAt(Instant now) {
        return blockedUntil != null && blockedUntil.isAfter(Objects.requireNonNull(now, "now"));
    }

    public boolean hasActiveReservationAt(Instant now) {
        return reservationId != null
                && reservationUntil != null
                && reservationUntil.isAfter(Objects.requireNonNull(now, "now"));
    }

    public void reserve(UUID token, Instant now, Duration duration) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("reservation duration must be positive");
        }
        reservationId = token;
        reservationUntil = now.plus(duration);
        updatedAt = now;
    }

    public boolean ownsReservation(UUID token) {
        return reservationId != null && reservationId.equals(token);
    }

    public void releaseReservation(UUID token, Instant now) {
        if (!ownsReservation(Objects.requireNonNull(token, "token"))) {
            throw new IllegalStateException(
                    "sale_operation_authorization_reservation_not_owned");
        }
        reservationId = null;
        reservationUntil = null;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public Failure registerFailure(
            UUID reservationToken,
            Instant now,
            Duration failureWindow,
            Duration cooldown) {
        if (!ownsReservation(Objects.requireNonNull(
                reservationToken, "reservationToken"))) {
            throw new IllegalStateException(
                    "sale_operation_authorization_reservation_not_owned");
        }
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(failureWindow, "failureWindow");
        Objects.requireNonNull(cooldown, "cooldown");
        if (failureWindow.isNegative() || failureWindow.isZero()) {
            throw new IllegalArgumentException("failureWindow must be positive");
        }
        if (cooldown.isNegative()) {
            throw new IllegalArgumentException("cooldown cannot be negative");
        }
        if (lastFailureAt == null
                || !lastFailureAt.plus(failureWindow).isAfter(now)) {
            consecutiveFailures = 0;
        }
        consecutiveFailures = consecutiveFailures == Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : consecutiveFailures + 1;
        lastFailureAt = now;
        blockedUntil = cooldown.isZero() ? null : now.plus(cooldown);
        reservationId = null;
        reservationUntil = null;
        updatedAt = now;
        return new Failure(consecutiveFailures, blockedUntil);
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public Instant getBlockedUntil() {
        return blockedUntil;
    }

    public Instant getLastFailureAt() {
        return lastFailureAt;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public Instant getReservationUntil() {
        return reservationUntil;
    }

    public record Failure(int consecutiveFailures, Instant blockedUntil) {
    }
}
