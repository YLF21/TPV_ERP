package com.tpverp.backend.sync;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SyncOutboxStatusService {

    private final SyncOutboxEventRepository repository;

    public SyncOutboxStatusService(SyncOutboxEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public SyncOutboxStatusView status() {
        return new SyncOutboxStatusView(
                repository.countByStatus(SyncOutboxStatus.PENDIENTE),
                repository.countByStatus(SyncOutboxStatus.ENVIANDO),
                repository.countByStatus(SyncOutboxStatus.ENVIADO),
                repository.countByStatus(SyncOutboxStatus.ERROR));
    }
}
