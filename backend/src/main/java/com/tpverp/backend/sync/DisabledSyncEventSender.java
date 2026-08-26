package com.tpverp.backend.sync;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${tpv.sync.central-url:}' == ''")
public class DisabledSyncEventSender implements SyncEventSender {

    @Override
    public void send(SyncOutboxEvent event) {
        throw new IllegalStateException("tpv.sync.central-url no configurado");
    }
}
