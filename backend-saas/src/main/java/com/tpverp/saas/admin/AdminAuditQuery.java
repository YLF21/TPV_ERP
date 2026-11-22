package com.tpverp.saas.admin;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminAuditQuery {
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "createdAt", "created_at", "username", "username", "action", "action", "targetType", "target_type",
            "targetId", "target_id", "details", "details", "id", "id");

    private final JdbcTemplate jdbc;

    public AdminAuditQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Filters(LocalDate from, LocalDate to, String username, String action, String q,
                          String sortBy, String sortDirection) {
        public Filters {
            username = text(username);
            action = text(action);
            q = text(q);
            sortBy = sortBy == null ? "createdAt" : sortBy;
            sortDirection = sortDirection == null ? "DESC" : sortDirection;
        }

        public void validate() {
            if (outsideRange(from) || outsideRange(to) || (from != null && to != null && to.isBefore(from))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Intervalo de fechas no válido");
            }
            if (tooLong(username) || tooLong(action) || tooLong(q)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filtro demasiado largo");
            }
            if (!SORT_COLUMNS.containsKey(sortBy) || !(sortDirection.equals("ASC") || sortDirection.equals("DESC"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ordenación no válida");
            }
        }

        private String orderBy() {
            String column = SORT_COLUMNS.get(sortBy);
            String direction = sortDirection.equals("ASC") ? "asc" : "desc";
            String order = column + " " + direction;
            if (column.equals("details")) order += " nulls last";
            return column.equals("id") ? order : order + ", id " + direction;
        }

        private static boolean outsideRange(LocalDate date) {
            return date != null && (date.getYear() < 1 || date.getYear() > 9999);
        }

        private static boolean tooLong(String text) {
            return text != null && text.length() > 250;
        }

        private static String text(String value) {
            if (value == null || value.isBlank()) return null;
            return value.trim();
        }
    }

    public record Page(List<AdminAuditLogResponse> items, int page, int size, long total) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page search(Filters filters, int page, int size) {
        filters.validate();
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paginación no válida");
        }
        return new Page(rows(filters, (long) page * size, size), page, size, count(filters));
    }

    public long count(Filters filters) {
        var args = new ArrayList<Object>();
        return jdbc.queryForObject("select count(*) " + where(filters, args), Long.class, args.toArray());
    }

    public List<AdminAuditLogResponse> rows(Filters filters, long offset, int limit) {
        var args = new ArrayList<Object>();
        String sql = "select id, username, action, target_type, target_id, details, created_at " + where(filters, args)
                + " order by " + filters.orderBy() + " limit ? offset ?";
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql, (rs, row) -> new AdminAuditLogResponse(rs.getObject("id", UUID.class),
                rs.getString("username"), rs.getString("action"), rs.getString("target_type"), rs.getString("target_id"),
                rs.getString("details"), rs.getTimestamp("created_at").toInstant()), args.toArray());
    }

    private static String where(Filters f, List<Object> args) {
        var sql = new StringBuilder("from saas_admin_audit_log where origin = 'SAAS_ADMIN'");
        if (f.from() != null) {
            sql.append(" and created_at >= ?");
            args.add(Timestamp.from(f.from().atStartOfDay().toInstant(ZoneOffset.UTC)));
        }
        if (f.to() != null) {
            sql.append(" and created_at < ?");
            args.add(Timestamp.from(f.to().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
        }
        if (f.username() != null) {
            sql.append(" and strpos(lower(username), lower(?)) > 0");
            args.add(f.username());
        }
        if (f.action() != null) {
            sql.append(" and strpos(lower(action), lower(?)) > 0");
            args.add(f.action());
        }
        if (f.q() != null) {
            sql.append(" and strpos(lower(concat_ws(' ',username,action,target_type,target_id,details)), lower(?)) > 0");
            args.add(f.q());
        }
        return sql.toString();
    }
}
