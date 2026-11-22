package com.tpverp.saas.access;

import static com.tpverp.saas.access.ClientAccessRequests.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClientAccessRequestQuery {
    private static final String SUMMARY_COLUMNS =
            "r.id, r.username, r.full_name, r.email, r.status, r.version, r.created_at, r.reviewed_at, r.reviewed_by";
    private static final Map<String, String> REQUEST_ORDER = Map.of(
            "username", "lower(r.username)", "fullName", "lower(r.full_name)", "email", "lower(r.email)",
            "status", "r.status", "createdAt", "r.created_at");
    private static final Map<String, String> DIRECTORY_ORDER = Map.of(
            "username", "lower(u.username)", "fullName", "lower(r.full_name)", "email", "lower(r.email)",
            "active", "u.active", "createdAt", "u.created_at", "companyCount", "company_count", "storeCount", "store_count");
    private final JdbcTemplate jdbc;

    public ClientAccessRequestQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Search(int page, int size, String q, String sort, String direction) {
        String order(Map<String, String> columns) {
            if (page < 0 || size < 1 || size > 100 || q.length() > 160 || q.indexOf(0) >= 0
                    || !columns.containsKey(sort) || !("asc".equalsIgnoreCase(direction) || "desc".equalsIgnoreCase(direction))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Consulta de directorio no válida");
            }
            return columns.get(sort) + ("desc".equalsIgnoreCase(direction) ? " desc" : " asc") + " nulls last, ";
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<Summary> requests(Search search, Status status) {
        String from = " from saas_client_access_request r where true";
        var args = new ArrayList<Object>();
        if (status != null) {
            from += " and r.status = ?";
            args.add(status.name());
        }
        if (!search.q().isBlank()) {
            from += " and strpos(lower(concat_ws(' ', r.username, r.full_name, r.email)), lower(?)) > 0";
            args.add(search.q().strip());
        }
        return page(search, SUMMARY_COLUMNS, from, search.order(REQUEST_ORDER) + "r.id", args,
                (rs, row) -> summary(rs));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<DirectoryUser> users(Search search) {
        String from = " from saas_tenant_user u left join saas_client_access_request r on r.tenant_user_id = u.id";
        var args = new ArrayList<Object>();
        if (!search.q().isBlank()) {
            from += " where strpos(lower(concat_ws(' ', u.username, r.full_name, r.email)), lower(?)) > 0";
            args.add(search.q().strip());
        }
        return page(search, """
                u.id, u.username, r.full_name, r.email, u.active, u.created_at,
                (select count(*) from saas_tenant_company_access a where a.user_id = u.id) company_count,
                (select count(*) from saas_tenant_store_access a where a.user_id = u.id) store_count
                """, from, search.order(DIRECTORY_ORDER) + "u.id", args,
                (rs, row) -> new DirectoryUser(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("full_name"), rs.getString("email"), rs.getBoolean("active"),
                        rs.getTimestamp("created_at").toInstant(), rs.getLong("company_count"), rs.getLong("store_count")));
    }

    private <T> Page<T> page(Search search, String projection, String from, String order,
            List<Object> args, RowMapper<T> mapper) {
        long total = jdbc.queryForObject("select count(*)" + from, Long.class, args.toArray());
        args.add(search.size());
        args.add((long) search.page() * search.size());
        return new Page<>(jdbc.query("select " + projection + from + " order by " + order + " limit ? offset ?",
                mapper, args.toArray()), total);
    }

    @Transactional(readOnly = true)
    public Detail detail(UUID id) {
        return jdbc.query("select " + SUMMARY_COLUMNS + """
                , tax_id, phone, address, postal_code, city, province, country, requested_stores, notes, review_reason,
                tenant_user_id, e.status as delivery_status, e.attempts, e.last_error, e.next_attempt_at, e.delivered_at
                from saas_client_access_request r left join saas_client_decision_email e on e.request_id=r.id where r.id = ?
                """, (rs, row) -> {
            var stores = rs.getArray("requested_stores");
            try {
                return new Detail(summary(rs), rs.getString("tax_id"), rs.getString("phone"), rs.getString("address"),
                        rs.getString("postal_code"), rs.getString("city"), rs.getString("province"), rs.getString("country"),
                        List.of((String[]) stores.getArray()), rs.getString("notes"), rs.getString("review_reason"),
                        rs.getObject("tenant_user_id", UUID.class), delivery(rs));
            } finally { stores.free(); }
        }, id).stream().findFirst().orElseThrow(ClientAccessRequestQuery::notFound);
    }

    private static Delivery delivery(ResultSet rs) throws SQLException {
        String status = rs.getString("delivery_status");
        if (status == null) return null;
        var next = rs.getTimestamp("next_attempt_at");
        var delivered = rs.getTimestamp("delivered_at");
        return new Delivery(status, rs.getInt("attempts"), rs.getString("last_error"),
                next == null ? null : next.toInstant(), delivered == null ? null : delivered.toInstant());
    }

    private static Summary summary(ResultSet rs) throws SQLException {
        var reviewed = rs.getTimestamp("reviewed_at");
        return new Summary(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("full_name"),
                rs.getString("email"), Status.valueOf(rs.getString("status")), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), reviewed == null ? null : reviewed.toInstant(),
                rs.getString("reviewed_by"));
    }

    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Solicitud de acceso no encontrada");
    }
}
