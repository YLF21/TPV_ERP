package com.tpverp.backend.control;

import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Presentation preferences only. Detection policy remains on the store's control rules. */
@Service
public class ControlAlertViewPreferenceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentOrganization organization;
    private final Clock clock;

    public ControlAlertViewPreferenceService(
            JdbcTemplate jdbc, ObjectMapper json, CurrentOrganization organization, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.organization = organization;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public View get(Authentication authentication) {
        var userId = organization.currentUser(authentication).getId();
        var settings = jdbc.query(
                "select opciones from preferencia_vista_alertas where usuario_id = ?",
                (rs, row) -> json.readValue(rs.getString(1), Settings.class), userId)
                .stream().findFirst().orElseGet(Settings::defaults);
        return view(settings);
    }

    @Transactional
    public View save(Settings settings, Authentication authentication) {
        if (settings == null) {
            throw new IllegalArgumentException("Las opciones de vista son obligatorias");
        }
        var userId = organization.currentUser(authentication).getId();
        var now = Timestamp.from(clock.instant());
        // Atomic first-save/update, including concurrent sessions of the same user.
        jdbc.update("""
                insert into preferencia_vista_alertas(usuario_id, opciones, created_at, updated_at)
                values (?, cast(? as jsonb), ?, ?)
                on conflict (usuario_id) do update
                    set opciones = excluded.opciones, updated_at = excluded.updated_at
                """, userId, json.writeValueAsString(settings), now, now);
        return view(settings);
    }

    private View view(Settings settings) {
        var store = organization.currentStore();
        return new View(settings.showIndicators(), settings.showDetail(), settings.groupByDay(),
                settings.compact(), settings.defaultPeriod(), settings.refreshSeconds(),
                settings.sortBy(), settings.sortDirection(), store.getTimezone(), store.getLocale());
    }

    public record Settings(
            Boolean showIndicators, Boolean showDetail, Boolean groupByDay, Boolean compact,
            String defaultPeriod, Integer refreshSeconds, String sortBy, String sortDirection) {
        public Settings {
            if (showIndicators == null || showDetail == null || groupByDay == null || compact == null) {
                throw new IllegalArgumentException("Las opciones de vista son obligatorias");
            }
            if (defaultPeriod == null || !Set.of("LAST_7_DAYS", "TODAY", "CURRENT_MONTH").contains(defaultPeriod)) {
                throw new IllegalArgumentException("Periodo inicial de alertas no valido");
            }
            if (refreshSeconds == null || !Set.of(0, 15, 30, 60).contains(refreshSeconds)) {
                throw new IllegalArgumentException("Intervalo de actualizacion no valido");
            }
            if (sortBy == null || !Set.of("occurredAt", "username", "terminal", "document", "detail", "status").contains(sortBy)) {
                throw new IllegalArgumentException("Ordenacion de alertas no valida");
            }
            if (sortDirection == null || !Set.of("asc", "desc").contains(sortDirection)) {
                throw new IllegalArgumentException("Direccion de ordenacion no valida");
            }
        }

        public static Settings defaults() {
            return new Settings(true, true, true, false, "LAST_7_DAYS", 30, "occurredAt", "desc");
        }
    }

    public record View(
            boolean showIndicators, boolean showDetail, boolean groupByDay, boolean compact,
            String defaultPeriod, int refreshSeconds, String sortBy, String sortDirection,
            String storeTimezone, String storeLocale) {}
}
