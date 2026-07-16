package com.tpverp.backend.document;

import java.util.Optional;
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
    public void release(CustomerPendingSaleCheckout checkout) {
        checkouts.delete(checkout);
        checkouts.flush();
    }
}
