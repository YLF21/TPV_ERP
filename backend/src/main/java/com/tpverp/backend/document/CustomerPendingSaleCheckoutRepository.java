package com.tpverp.backend.document;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface CustomerPendingSaleCheckoutRepository
        extends JpaRepository<CustomerPendingSaleCheckout, UUID> {

    Optional<CustomerPendingSaleCheckout> findByTerminalIdAndCheckoutId(
            UUID terminalId, UUID checkoutId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select checkout from CustomerPendingSaleCheckout checkout where checkout.id=:id")
    Optional<CustomerPendingSaleCheckout> findLockedById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select checkout from CustomerPendingSaleCheckout checkout
            where checkout.terminalId=:terminalId and checkout.checkoutId=:checkoutId
            """)
    Optional<CustomerPendingSaleCheckout> findLockedByTerminalIdAndCheckoutId(
            @Param("terminalId") UUID terminalId, @Param("checkoutId") UUID checkoutId);
}
