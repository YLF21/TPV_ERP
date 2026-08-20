package com.tpverp.backend.party.loyalty.points.bootstrap;

import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class MemberPointsBootstrapScheduler {
    private final MemberPointsBootstrapWorker worker;
    private final Environment environment;

    public MemberPointsBootstrapScheduler(
            MemberPointsBootstrapWorker worker,
            Environment environment) {
        this.worker = worker;
        this.environment = environment;
    }

    @Scheduled(fixedDelayString = "${tpv.sync.member-points-bootstrap-delay-ms:30000}")
    public void tick() {
        if (StringUtils.hasText(environment.getProperty("tpv.sync.central-url"))) {
            worker.runOnce();
        }
    }
}
