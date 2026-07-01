package com.tpverp.backend.sync;

import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class SyncOutboxWorker {

    private final SyncOutboxService outbox;
    private final SyncEventSender sender;
    private final Clock clock;

    public SyncOutboxWorker(SyncOutboxService outbox, SyncEventSender sender, Clock clock) {
        this.outbox = outbox;
        this.sender = sender;
        this.clock = clock;
    }

    public int runOnce() {
        int sent = 0;
        for (var event : outbox.pending()) {
            event.markSending();
            try {
                sender.send(event);
                event.markSent(clock.instant());
                sent++;
            } catch (RuntimeException exception) {
                event.markError(exception.getMessage() == null
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage());
            }
            outbox.save(event);
        }
        return sent;
    }
}
