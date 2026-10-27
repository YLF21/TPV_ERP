package com.tpverp.saas.marketing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

@Repository
public class DemoRequestRepository {

    private static final String COLUMNS = """
            id, product, products, contact_name, company_name, email, phone, message, locale,
            landing_path, referrer, utm_source, utm_medium, utm_campaign,
            privacy_accepted_at, status, created_at,
            (select u.username from saas_admin_user u where u.id = assignee_user_id) as assignee_username,
            next_action, next_action_at, follow_up_version
            """;
    private static final RowMapper<DemoRequestView> MAPPER = (rs, rowNum) -> new DemoRequestView(
            rs.getObject("id", UUID.class), DemoProduct.valueOf(rs.getString("product")), readProducts(rs),
            rs.getString("contact_name"), rs.getString("company_name"), rs.getString("email"),
            rs.getString("phone"), rs.getString("message"), rs.getString("locale"),
            rs.getString("landing_path"), rs.getString("referrer"), rs.getString("utm_source"),
            rs.getString("utm_medium"), rs.getString("utm_campaign"), rs.getString("status"),
            rs.getTimestamp("privacy_accepted_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
            rs.getString("assignee_username"), rs.getString("next_action"),
            instant(rs, "next_action_at"), rs.getLong("follow_up_version"));

    private final JdbcTemplate jdbc;

    public DemoRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(DemoRequestView request) {
        save(request, null, null);
    }

    /** ON CONFLICT avoids poisoning the transaction when two instances receive the same retry. */
    public boolean save(DemoRequestView request, UUID idempotencyKey, String fingerprint) {
        return jdbc.update("""
                insert into saas_marketing_demo_request
                    (id, product, products, contact_name, company_name, email, phone, message, locale,
                     landing_path, referrer, utm_source, utm_medium, utm_campaign,
                     privacy_accepted_at, status, created_at, idempotency_key, request_fingerprint)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (idempotency_key) do nothing
                """,
                request.id(), request.product().name(),
                new SqlArrayValue("varchar", request.products().stream().map(Enum::name).toArray(Object[]::new)),
                request.name(), request.company(), request.email(),
                request.phone(), request.message(), request.locale(), request.landingPath(), request.referrer(),
                request.utmSource(), request.utmMedium(), request.utmCampaign(), Timestamp.from(request.privacyAcceptedAt()),
                request.status(), Timestamp.from(request.createdAt()), idempotencyKey, fingerprint) == 1;
    }

    public boolean available() {
        return jdbc.queryForObject("""
                select count(*) from (
                    select id, idempotency_key, request_fingerprint
                    from saas_marketing_demo_request where false
                ) ready
                """, Long.class) != null;
    }

    public Optional<StoredSubmission> findSubmission(UUID key) {
        return jdbc.query("""
                select id, created_at, request_fingerprint from saas_marketing_demo_request
                where idempotency_key = ?
                """, (rs, rowNum) -> new StoredSubmission(rs.getString("request_fingerprint"),
                new DemoRequestReceipt(rs.getObject("id", UUID.class),
                        rs.getTimestamp("created_at").toInstant())), key).stream().findFirst();
    }

    public record StoredSubmission(String fingerprint, DemoRequestReceipt receipt) { }

    public List<DemoRequestView> findLatest(int limit) {
        return jdbc.query("select " + COLUMNS + " from saas_marketing_demo_request"
                + " order by created_at desc, id desc limit ?", MAPPER, limit);
    }

    public DemoRequestPage page(int page, int size, DemoRequestStatus status, String query) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> arguments = new ArrayList<>();
        if (status != null) {
            where.append(" and status = ?");
            arguments.add(status.name());
        }
        if (query != null) {
            UUID reference = canonicalReference(query);
            if (reference != null) {
                // A receipt reference identifies only its own record, even if another contact
                // happens to mention that UUID in a searchable text field.
                where.append(" and id = ?");
                arguments.add(reference);
            } else {
                // Literal substring search, not a caller-controlled LIKE expression or SQL fragment.
                String pattern = "%" + query.toLowerCase(java.util.Locale.ROOT)
                        .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
                where.append(" and (lower(contact_name) like ? escape '!'"
                        + " or lower(company_name) like ? escape '!' or lower(email) like ? escape '!')");
                arguments.add(pattern);
                arguments.add(pattern);
                arguments.add(pattern);
            }
        }
        Long total = jdbc.queryForObject("select count(*) from saas_marketing_demo_request" + where,
                Long.class, arguments.toArray());
        arguments.add(size);
        arguments.add((long) page * size);
        List<DemoRequestView> items = jdbc.query("select " + COLUMNS + " from saas_marketing_demo_request"
                + where + " order by created_at desc, id desc limit ? offset ?", MAPPER, arguments.toArray());
        return new DemoRequestPage(items, total == null ? 0 : total, page, size);
    }

    public Optional<DemoRequestView> updateStatus(UUID id, DemoRequestStatus status, DemoRequestStatus expectedStatus) {
        return jdbc.query("update saas_marketing_demo_request set status = ? where id = ? and status = ? returning "
                + COLUMNS, MAPPER, status.name(), id, expectedStatus.name()).stream().findFirst();
    }

    public Optional<DemoRequestView> findById(UUID id) {
        return jdbc.query("select " + COLUMNS + " from saas_marketing_demo_request where id = ?",
                MAPPER, id).stream().findFirst();
    }

    private static UUID canonicalReference(String query) {
        try {
            UUID reference = UUID.fromString(query);
            return reference.toString().equalsIgnoreCase(query) ? reference : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static List<DemoProduct> readProducts(ResultSet rs) throws SQLException {
        var products = rs.getArray("products");
        try {
            return Arrays.stream((String[]) products.getArray()).map(DemoProduct::valueOf).toList();
        } finally {
            products.free();
        }
    }

    private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
