package com.tpverp.backend.party;

import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MemberBalanceExpirationScheduler {

    private final MemberLoyaltyService members;
    private final Environment environment;

    public MemberBalanceExpirationScheduler(MemberLoyaltyService members, Environment environment) {
        this.members = members;
        this.environment = environment;
    }

    @Scheduled(cron = "${tpv.members.balance-expiration-cron:0 10 3 * * *}")
    public void expire() {
        if (Boolean.TRUE.equals(environment.getProperty(
                "tpv.members.balance-expiration-enabled", Boolean.class, false))) {
            members.expireBalanceLots();
        }
    }
    // Runs lot expiration only when explicitly enabled for the installation.
}
