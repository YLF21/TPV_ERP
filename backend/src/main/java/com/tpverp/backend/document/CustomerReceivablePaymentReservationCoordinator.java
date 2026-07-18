package com.tpverp.backend.document;

import com.tpverp.backend.document.CustomerReceivablePaymentReservation.Kind;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerReceivablePaymentReservationCoordinator {

    private static final Duration LEASE = Duration.ofSeconds(30);
    private final CommercialDocumentRepository documents;
    private final CustomerReceivablePaymentReservationRepository reservations;
    private final Clock clock;

    public CustomerReceivablePaymentReservationCoordinator(
            CommercialDocumentRepository documents,
            CustomerReceivablePaymentReservationRepository reservations,
            Clock clock) {
        this.documents = documents;
        this.reservations = reservations;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Acquisition acquire(
            UUID paymentId, UUID documentId, UUID storeId, UUID terminalId, UUID userId,
            String requestHash, BigDecimal amount, Kind kind, UUID owner) {
        var now = Instant.now(clock);
        var document = documents.findLockedReceivable(documentId, storeId)
                .orElseThrow(() -> new IllegalArgumentException("customer_receivable_not_found"));
        requireCollectable(document);
        var existing = reservations.findLockedById(paymentId);
        if (existing.isPresent()) {
            var current = existing.orElseThrow();
            if (!current.sameIdentity(documentId, storeId, terminalId, userId,
                    requestHash, amount, kind)) {
                throw new IllegalStateException("payment_idempotency_conflict");
            }
            if (current.getStatus() == CustomerReceivablePaymentReservation.Status.COMPLETED) {
                return new Acquisition(current, false, true);
            }
            if (current.getStatus() == CustomerReceivablePaymentReservation.Status.APPROVED) {
                return new Acquisition(current, false, false);
            }
            if (current.getStatus() == CustomerReceivablePaymentReservation.Status.RELEASED) {
                requireAvailable(document, reservations.findAllLockedByDocumentId(documentId), amount, now);
                current.reactivate(owner, now.plus(LEASE), now);
                return new Acquisition(reservations.save(current), true, false);
            }
            if (!current.claim(owner, now.plus(LEASE), now)) {
                throw new IllegalStateException("receivable_payment_in_progress");
            }
            return new Acquisition(reservations.save(current), true, false);
        }

        var active = reservations.findAllLockedByDocumentId(documentId);
        active.stream().filter(value -> value.hasExpiredReservedLease(now))
                .forEach(value -> value.release(now));
        reservations.saveAll(active);
        requireAvailable(document, active, amount, now);
        var normalized = Money.euros(amount);
        var created = CustomerReceivablePaymentReservation.reserve(
                paymentId, documentId, storeId, terminalId, userId, requestHash, normalized,
                kind, owner, now.plus(LEASE), now);
        return new Acquisition(reservations.saveAndFlush(created), true, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDispatching(UUID paymentId, UUID owner) {
        var reservation = reservations.findLockedById(paymentId)
                .orElseThrow(() -> new IllegalStateException("receivable_payment_reservation_missing"));
        reservation.markDispatching(owner, Instant.now(clock));
        reservations.save(reservation);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordTerminalResult(
            UUID paymentId, PaymentTerminalOperationStatus status) {
        var reservation = reservations.findLockedById(paymentId)
                .orElseThrow(() -> new IllegalStateException("receivable_payment_reservation_missing"));
        reservation.recordTerminalResult(status, Instant.now(clock));
        reservations.save(reservation);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID paymentId, UUID owner) {
        var reservation = reservations.findLockedById(paymentId).orElse(null);
        if (reservation == null || !reservation.isOwnedBy(owner)
                || reservation.getStatus() == CustomerReceivablePaymentReservation.Status.DISPATCHING
                || reservation.getStatus() == CustomerReceivablePaymentReservation.Status.APPROVED) return;
        reservation.release(Instant.now(clock));
        reservations.save(reservation);
    }

    public record Acquisition(
            CustomerReceivablePaymentReservation reservation,
            boolean acquired, boolean completedReplay) {}

    private static void requireAvailable(
            CommercialDocument document,
            java.util.List<CustomerReceivablePaymentReservation> reservations,
            BigDecimal amount, Instant now) {
        var reserved = reservations.stream()
                .filter(value -> !value.hasExpiredReservedLease(now))
                .filter(CustomerReceivablePaymentReservation::reservesBalance)
                .map(CustomerReceivablePaymentReservation::getAmount)
                .reduce(Money.euros(BigDecimal.ZERO), BigDecimal::add);
        var normalized = Money.euros(amount);
        if (normalized.signum() <= 0
                || reserved.add(normalized).compareTo(Money.euros(document.getPendingTotal())) > 0) {
            throw new IllegalArgumentException("message.document.payment_exceeds_pending_total");
        }
    }

    private static void requireCollectable(CommercialDocument document) {
        if (document.getTipo() != CommercialDocumentType.ALBARAN_VENTA
                && document.getTipo() != CommercialDocumentType.FACTURA_VENTA) {
            throw new IllegalStateException("message.document.only_receivable_document_can_be_paid");
        }
        if (document.getClienteId() == null) {
            throw new IllegalStateException("customer_receivable_customer_required");
        }
        if ((document.getEstado() != DocumentStatus.PENDIENTE
                && document.getEstado() != DocumentStatus.PARCIAL)
                || document.getPendingTotal().signum() <= 0) {
            throw new IllegalStateException("customer_receivable_not_collectable");
        }
    }
}
