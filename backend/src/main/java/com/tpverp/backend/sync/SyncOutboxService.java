package com.tpverp.backend.sync;

import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SyncOutboxService {

    private final SyncOutboxEventRepository repository;
    private final Clock clock;

    public SyncOutboxService(SyncOutboxEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public SyncOutboxEvent enqueue(SyncOutboundEventCommand command) {
        var event = new SyncOutboxEvent(
                command.companyId(),
                command.storeId(),
                command.terminalId(),
                command.entityType(),
                command.entityId(),
                command.operation(),
                command.payload(),
                clock.instant());
        repository.save(event);
        return event;
    }

    @Transactional(readOnly = true)
    public List<SyncOutboxEvent> pending() {
        return repository.findByStatusOrderByCreatedAtAsc(SyncOutboxStatus.PENDIENTE);
    }

    @Transactional
    public SyncOutboxEvent save(SyncOutboxEvent event) {
        return repository.save(event);
    }
}
