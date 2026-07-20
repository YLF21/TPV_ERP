package com.tpverp.backend.document;

import java.util.Optional;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerPendingSaleCheckoutReservation {

    private final CustomerPendingSaleCheckoutRepository checkouts;

    public CustomerPendingSaleCheckoutReservation(CustomerPendingSaleCheckoutRepository checkouts) {
        this.checkouts = checkouts;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<CustomerPendingSaleCheckout> find(UUID terminalId, UUID checkoutId) {
        return checkouts.findByTerminalIdAndCheckoutId(terminalId, checkoutId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CustomerPendingSaleCheckout insert(CustomerPendingSaleCheckout checkout) {
        return checkouts.saveAndFlush(checkout);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public CustomerPendingSaleCheckout findAfterConflict(UUID terminalId, UUID checkoutId) {
        return checkouts.findByTerminalIdAndCheckoutId(terminalId, checkoutId)
                .orElseThrow(() -> new IllegalStateException(
                        "pending_sale_checkout_race_winner_not_found"));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CustomerPendingSaleCheckout claim(
            UUID terminalId, UUID checkoutId, UUID storeId, UUID userId,
            String hash, UUID owner, Instant leaseUntil, Instant now) {
        var checkout = checkouts.findLockedByTerminalIdAndCheckoutId(terminalId, checkoutId)
                .orElseThrow(() -> new IllegalStateException("pending_sale_checkout_not_found"));
        if (!checkout.matchesScope(storeId, userId)) {
            throw new IllegalStateException("pending_sale_checkout_scope_mismatch");
        }
        if (!checkout.matchesHash(hash)) {
            throw new IllegalStateException("pending_sale_checkout_idempotency_conflict");
        }
        if (!checkout.isCompleted() && !checkout.claim(owner, leaseUntil, now)) {
            throw new IllegalStateException("pending_sale_checkout_in_progress");
        }
        return checkouts.save(checkout);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockOwned(UUID checkoutId, UUID owner) {
        var checkout = checkouts.findLockedById(checkoutId)
                .orElseThrow(() -> new IllegalStateException("pending_sale_checkout_not_found"));
        if (!checkout.isOwnedBy(owner)) {
            throw new IllegalStateException("pending_sale_checkout_lease_lost");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID checkoutId, UUID owner) {
        var checkout = checkouts.findLockedById(checkoutId).orElse(null);
        if (checkout == null || checkout.isCompleted() || !checkout.isOwnedBy(owner)) return;
        checkouts.delete(checkout);
        checkouts.flush();
    }
}
