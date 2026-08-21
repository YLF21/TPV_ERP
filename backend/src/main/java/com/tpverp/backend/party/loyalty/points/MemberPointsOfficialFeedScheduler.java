package com.tpverp.backend.party.loyalty.points;

import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class MemberPointsOfficialFeedScheduler {
    private final MemberPointsOfficialFeedWorker worker;
    private final Environment environment;

    public MemberPointsOfficialFeedScheduler(
            MemberPointsOfficialFeedWorker worker,
            Environment environment) {
        this.worker = worker;
        this.environment = environment;
    }

    @Scheduled(fixedDelayString = "${tpv.sync.member-points-feed-delay-ms:15000}")
    public void tick() {
        if (StringUtils.hasText(environment.getProperty("tpv.sync.central-url"))) {
            worker.runOnce();
        }
    }
}
