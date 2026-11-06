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
    public ReplenishmentExpirationService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public int expire(UUID storeId, LocalDate today) {
        // PostgreSQL month arithmetic clamps to the last day of shorter months.
        // Re-check status/date in the DELETE so concurrent unmarking protects a request.
        return jdbc.update("""
            delete from replenishment_request
            where store_id = :storeId and status = 'ORDERED'
              and order_date + interval '1 month' <= cast(:today as date)
            """, Map.of("storeId", storeId, "today", java.sql.Date.valueOf(today)));
    }
}
