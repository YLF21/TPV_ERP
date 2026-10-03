package com.tpverp.saas.marketing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

@Repository
public class DemoRequestRepository {

    private final JdbcTemplate jdbc;

    public DemoRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(DemoRequestView request) {
        jdbc.update("""
                insert into saas_marketing_demo_request
                    (id, product, products, contact_name, company_name, email, phone, message, locale,
                     landing_path, referrer, utm_source, utm_medium, utm_campaign,
                     privacy_accepted_at, status, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                request.id(), request.product().name(),
                new SqlArrayValue("varchar", request.products().stream().map(Enum::name).toArray(Object[]::new)),
                request.name(), request.company(), request.email(),
                request.phone(), request.message(), request.locale(), request.landingPath(), request.referrer(),
                request.utmSource(), request.utmMedium(), request.utmCampaign(), Timestamp.from(request.privacyAcceptedAt()),
                request.status(), Timestamp.from(request.createdAt()));
    }

    public List<DemoRequestView> findLatest(int limit) {
        return jdbc.query("""
                select id, product, products, contact_name, company_name, email, phone, message, locale,
                       landing_path, referrer, utm_source, utm_medium, utm_campaign,
                       privacy_accepted_at, status, created_at
                  from saas_marketing_demo_request
                 order by created_at desc, id desc
                 limit ?
                """, (rs, rowNum) -> new DemoRequestView(
                rs.getObject("id", UUID.class),
                DemoProduct.valueOf(rs.getString("product")),
                readProducts(rs),
                rs.getString("contact_name"),
                rs.getString("company_name"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("message"),
                rs.getString("locale"),
                rs.getString("landing_path"),
                rs.getString("referrer"),
                rs.getString("utm_source"),
                rs.getString("utm_medium"),
                rs.getString("utm_campaign"),
                rs.getString("status"),
                rs.getTimestamp("privacy_accepted_at").toInstant(),
                rs.getTimestamp("created_at").toInstant()), limit);
    }

    private static List<DemoProduct> readProducts(ResultSet rs) throws SQLException {
        var products = rs.getArray("products");
        try {
            return Arrays.stream((String[]) products.getArray()).map(DemoProduct::valueOf).toList();
        } finally {
            products.free();
        }
    }
}
