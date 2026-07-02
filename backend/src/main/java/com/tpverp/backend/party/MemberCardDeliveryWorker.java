package com.tpverp.backend.party;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberCardDeliveryWorker {

    private final MemberCardDeliveryRepository deliveries;
    private final MemberCardSender sender;
    private final Clock clock;

    public MemberCardDeliveryWorker(
            MemberCardDeliveryRepository deliveries,
            MemberCardSender sender,
            Clock clock) {
        this.deliveries = deliveries;
        this.sender = sender;
        this.clock = clock;
    }

    @Transactional
    public int runOnce() {
        int sent = 0;
        for (var delivery : deliveries.findByStatusOrderByCreatedAtAsc(MemberCardDeliveryStatus.PENDIENTE)) {
            try {
                sender.send(delivery);
                delivery.markSent(clock.instant());
                sent++;
            } catch (RuntimeException exception) {
                delivery.markError(errorMessage(exception));
            }
        }
        return sent;
    }
    // Processes pending card deliveries and keeps failed ones visible for manual retry.

    private static String errorMessage(RuntimeException exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }
}
