package com.tpverp.backend.party.loyalty.category;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty("tpv.sync.central-url")
public class MemberCategoryBootstrapScheduler {
    private final MemberCategoryBootstrapWorker worker;

    public MemberCategoryBootstrapScheduler(MemberCategoryBootstrapWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${tpv.sync.member-category-bootstrap-delay-ms:30000}")
    public void tick() {
        worker.runOnce();
    }
}
