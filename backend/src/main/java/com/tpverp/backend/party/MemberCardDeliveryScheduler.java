package com.tpverp.backend.party;

import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MemberCardDeliveryScheduler {

    private final MemberCardDeliveryWorker worker;
    private final Environment environment;

    public MemberCardDeliveryScheduler(MemberCardDeliveryWorker worker, Environment environment) {
        this.worker = worker;
        this.environment = environment;
    }

    @Scheduled(fixedDelayString = "${tpv.members.card-delivery-delay-ms:60000}")
    public void tick() {
        if (Boolean.TRUE.equals(environment.getProperty(
                "tpv.members.card-delivery-enabled", Boolean.class, false))) {
            worker.runOnce();
        }
    }
}
