package com.tpverp.backend.sync;

public interface SyncEventSender {

    void send(SyncOutboxEvent event);
}
