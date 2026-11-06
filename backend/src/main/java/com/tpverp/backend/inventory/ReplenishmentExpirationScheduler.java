package com.tpverp.backend.inventory;

import com.tpverp.backend.organization.StoreRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReplenishmentExpirationScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(ReplenishmentExpirationScheduler.class);
    private final StoreRepository stores;
    private final ReplenishmentExpirationService expiration;
    public ReplenishmentExpirationScheduler(StoreRepository stores, ReplenishmentExpirationService expiration) {
        this.stores = stores; this.expiration = expiration;
    }

    @Scheduled(fixedDelayString = "${tpv.replenishment.expiration-delay-ms:3600000}",
            initialDelayString = "${tpv.replenishment.expiration-initial-delay-ms:30000}")
    public void expire() {
        try {
            for (var store : stores.findAll()) {
                try { expiration.expire(store.getId(), LocalDate.now(ZoneId.of(store.getTimezone()))); }
                catch (RuntimeException failure) { LOG.warn("REPLENISHMENT_EXPIRATION_PENDING store={}", store.getId()); }
            }
        } catch (RuntimeException failure) { LOG.warn("REPLENISHMENT_EXPIRATION_UNAVAILABLE"); }
    }
}
