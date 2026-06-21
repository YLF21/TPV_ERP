package com.tpverp.backend.verifactu;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class VerifactuClockService {

    private static final long THRESHOLD_SECONDS = 300;
    private static final String DRIFT_WARNING = "CLOCK_DRIFT_OVER_5_MINUTES";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public VerifactuClockService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public VerifactuClockStatusView check() {
        var applicationTime = Instant.now(clock);
        var databaseTime = databaseTime();
        var driftSeconds = Duration.between(applicationTime, databaseTime).toSeconds();
        var warning = Math.abs(driftSeconds) > THRESHOLD_SECONDS;
        return new VerifactuClockStatusView(
                warning,
                warning ? DRIFT_WARNING : null,
                applicationTime,
                databaseTime,
                driftSeconds,
                THRESHOLD_SECONDS,
                applicationTime);
    }
    // Compara la hora del servidor Java con PostgreSQL sin corregir el reloj del sistema.

    private Instant databaseTime() {
        return jdbc.queryForObject("select current_timestamp", Timestamp.class).toInstant();
    }
}
