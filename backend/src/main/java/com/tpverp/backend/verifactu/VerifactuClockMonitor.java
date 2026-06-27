package com.tpverp.backend.verifactu;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class VerifactuClockMonitor {

    private final VerifactuClockService service;
    private final AtomicReference<VerifactuClockStatusView> lastStatus = new AtomicReference<>();

    public VerifactuClockMonitor(VerifactuClockService service) {
        this.service = service;
    }

    public VerifactuClockStatusView current() {
        var current = lastStatus.get();
        return current == null ? refresh() : current;
    }
    // Returns the last check or calculates it on demand when none exists.

    @EventListener(ApplicationReadyEvent.class)
    public void checkOnStartup() {
        refresh();
    }
    // Revisa la diferencia horaria al arrancar el backend.

    @Scheduled(cron = "${tpv.verifactu.clock-check-cron:0 0 9 * * *}")
    public void checkDaily() {
        refresh();
    }
    // Repite la comprobacion una vez al dia sin modificar la hora de Windows.

    private VerifactuClockStatusView refresh() {
        var status = service.check();
        lastStatus.set(status);
        return status;
    }
}
