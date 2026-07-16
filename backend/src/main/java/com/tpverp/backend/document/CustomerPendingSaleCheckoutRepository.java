package com.tpverp.backend.document;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerPendingSaleCheckoutRepository
        extends JpaRepository<CustomerPendingSaleCheckout, UUID> {

    Optional<CustomerPendingSaleCheckout> findByTerminalIdAndCheckoutId(
            UUID terminalId, UUID checkoutId);
}
