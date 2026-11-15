package com.tpverp.saas.sync;

import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReplenishmentExpiryService {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ReplenishmentExpiryService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tpv.saas.replenishment.expiry-delay-ms:3600000}")
    @Transactional
    public int purgeExpired() {
        return jdbc.update("update saas_replenishment_request set state='EXPIRED', "
                        + "product_id=null,warehouse_id=null,quantity=null,reason=null,order_date=null, "
                        + "created_at=null,expires_at=null,metadata_json=null "
                        + "where state='ORDERED' and expires_at <= ?",
                java.sql.Timestamp.from(clock.instant()));
    }
}
