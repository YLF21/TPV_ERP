package com.tpverp.backend.sync;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "tpv.sync.central-url", havingValue = "", matchIfMissing = true)
public class DisabledSyncEventSender implements SyncEventSender {

    @Override
    public void send(SyncOutboxEvent event) {
        throw new IllegalStateException("tpv.sync.central-url no configurado");
    }
}
