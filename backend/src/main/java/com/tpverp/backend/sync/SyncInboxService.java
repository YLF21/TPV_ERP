package com.tpverp.backend.sync;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SyncInboxService {

    private final SyncInboxEventRepository repository;
    private final Clock clock;

    public SyncInboxService(SyncInboxEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public SyncInboxReceipt receive(SyncInboundEventRequest request) {
        var existing = repository.findByEventId(request.eventId());
        if (existing.isPresent()) {
            return new SyncInboxReceipt(request.eventId(), SyncInboxResult.DUPLICADO);
        }
        repository.save(new SyncInboxEvent(
                request.eventId(), request.companyId(), request.storeId(), clock.instant()));
        return new SyncInboxReceipt(request.eventId(), SyncInboxResult.OK);
    }
}
