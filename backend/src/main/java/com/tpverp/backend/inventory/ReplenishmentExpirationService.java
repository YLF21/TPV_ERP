package com.tpverp.backend.inventory;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReplenishmentExpirationService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ReplenishmentSyncPublisher sync;
    public ReplenishmentExpirationService(NamedParameterJdbcTemplate jdbc, ReplenishmentSyncPublisher sync) {
        this.jdbc = jdbc; this.sync = sync;
    }

    @Transactional
    public int expire(UUID storeId, LocalDate today) {
        // PostgreSQL month arithmetic clamps to the last day of shorter months.
        // Re-check status/date in the DELETE so concurrent unmarking protects a request.
        var deleted = jdbc.query("""
            with expired as (
                select id from replenishment_request
                where store_id = :storeId and status = 'ORDERED'
                  and order_date + interval '1 month' <= cast(:today as date)
                order by id for update skip locked
            )
            delete from replenishment_request r using expired
            where r.id = expired.id and r.store_id = :storeId and r.status = 'ORDERED'
              and r.order_date + interval '1 month' <= cast(:today as date)
            returning r.id, r.version
            """, Map.of("storeId", storeId, "today", java.sql.Date.valueOf(today)),
                (rs, row) -> new ReplenishmentService.Item(rs.getObject("id", UUID.class), rs.getLong("version")));
        for (var request : deleted) sync.deleted(storeId, request.id(), Math.addExact(request.version(), 1));
        return deleted.size();
    }
}
