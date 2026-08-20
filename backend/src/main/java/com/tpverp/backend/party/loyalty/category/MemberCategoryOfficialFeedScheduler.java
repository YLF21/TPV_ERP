package com.tpverp.backend.party.loyalty.category;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty("tpv.sync.central-url")
public class MemberCategoryOfficialFeedScheduler {
    private final MemberCategoryOfficialFeedWorker worker;

    public MemberCategoryOfficialFeedScheduler(MemberCategoryOfficialFeedWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${tpv.sync.member-category-feed-delay-ms:15000}")
    public void tick() {
        worker.runOnce();
    }
}
