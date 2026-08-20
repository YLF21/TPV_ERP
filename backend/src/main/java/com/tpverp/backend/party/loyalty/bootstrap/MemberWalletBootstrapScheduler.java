package com.tpverp.backend.party.loyalty.bootstrap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(
        prefix = "tpv.sync",
        name = "member-wallet-bootstrap-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class MemberWalletBootstrapScheduler {

    private final MemberWalletBootstrapWorker worker;
    private final Environment environment;

    public MemberWalletBootstrapScheduler(
            MemberWalletBootstrapWorker worker,
            Environment environment) {
        this.worker = worker;
        this.environment = environment;
    }

    @Scheduled(fixedDelayString = "${tpv.sync.member-wallet-bootstrap-delay-ms:60000}")
    public void tick() {
        if (StringUtils.hasText(environment.getProperty("tpv.sync.central-url"))) {
            worker.runOnce();
        }
    }
}
