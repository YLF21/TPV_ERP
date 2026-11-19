package com.tpverp.saas.analytics;

import static com.tpverp.saas.analytics.AdminSummary.*;
import static com.tpverp.saas.license.SaasLicenseRepository.EFFECTIVE_STATUS_SQL;

import com.tpverp.saas.license.LicenseSaasStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminSummaryService {
    private final NamedParameterJdbcTemplate jdbc;
    private final CommercialDocumentSummaryRepository documents;
    private final Clock clock;
    private final ReportStatisticsService statistics;

    public AdminSummaryService(NamedParameterJdbcTemplate jdbc, CommercialDocumentSummaryRepository documents,
                               Clock clock, ReportStatisticsService statistics) {
        this.statistics = statistics;
        this.jdbc = jdbc;
        this.documents = documents;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminSummary summary() {
        return summary(null, null, null, null, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminSummary summary(LocalDate from, LocalDate to, UUID companyId, UUID storeId, String province) {
        Instant now = clock.instant();
        if (from == null && to == null) {
            to = LocalDate.ofInstant(now, ZoneOffset.UTC);
            from = to.minusDays(29);
        }
        var period = ReportStatistics.Period.inclusive(from, to);
        var scope = new AnalyticsScope(companyId, storeId, province);
        var activity = statistics.aggregate(period, null, ReportStatistics.GroupBy.DAY, scope, 0, period.days());
        var params = scope.parameters().addValue("now", Timestamp.from(now))
                .addValue("start", Timestamp.from(from.atStartOfDay(ZoneOffset.UTC).toInstant()))
                .addValue("end", Timestamp.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
        String links = "with linked_stores as (" + AnalyticsScope.LICENSE_LINKS + ") ";
        String created = "count(*) filter(where created_at >= :start and created_at < :end)";
        long[] companies = jdbc.queryForObject("select count(*)," + created + " from saas_company c where true"
                + scope.companies(), params, (row, n) -> new long[]{row.getLong(1), row.getLong(2)});
        long[] stores = jdbc.queryForObject("select count(*),count(*) filter(where active)," + created
                + " from saas_store s where true" + scope.stores(), params,
                (row, n) -> new long[]{row.getLong(1),row.getLong(2),row.getLong(3)});
        long[] licenses = jdbc.queryForObject(links + """
                select count(*), count(*) filter (where state='VALIDA'), count(*) filter (where state='CADUCADA'),
                       count(*) filter (where state='BLOQUEADA_MANUAL'), %s
                from (select %s as state,l.created_at from saas_license l where true %s) states
                """.formatted(created, EFFECTIVE_STATUS_SQL, scope.licenses()), params,
                (row, n) -> new long[]{row.getLong(1),row.getLong(2),row.getLong(3),row.getLong(4),row.getLong(5)});
        long installations = jdbc.queryForObject("select count(*) from saas_installation i where true" + scope.facts("i"),
                params, Long.class);
        var recent = jdbc.query(links + """
                , recent as (
                    select l.id, l.company_id, l.store_id, l.reference, %s as state, l.created_at, l.valid_until
                    from saas_license l where true %s order by l.created_at desc, l.id limit 8
                )
                select l.id, l.reference, c.name company_name, l.state, l.created_at, l.valid_until,
                       count(s.id) store_count, case when count(s.id)=1 then min(s.name) end store_name,
                       case when count(s.id)>0 and count(distinct (%s))=1 then min(%s) end province
                from recent l join saas_company c on c.id=l.company_id
                left join linked_stores link on link.license_id=l.id and link.company_id=l.company_id
                left join saas_store s on s.company_id=l.company_id and s.id=link.store_id
                where true %s
                group by l.id, l.reference, c.name, l.state, l.created_at, l.valid_until
                order by l.created_at desc, l.id
                """.formatted(EFFECTIVE_STATUS_SQL, scope.licenses(), AnalyticsScope.PROVINCE, AnalyticsScope.PROVINCE,
                        scope.store() == null && scope.province() == null ? "" : scope.stores()), params, (row, n) -> new RecentLicense(
                row.getObject("id", UUID.class), row.getString("reference"), row.getString("company_name"),
                row.getLong("store_count"), row.getString("store_name"), LicenseSaasStatus.valueOf(row.getString("state")),
                instant(row, "created_at"), instant(row, "valid_until"), row.getString("province")));
        var daily = activity.rows().stream().map(row -> new Daily(row.label(), row.current().syncEvents(),
                row.current().currencies())).toList();
        return new AdminSummary(now, companies[0], new Stores(stores[0], stores[1]),
                new Licenses(licenses[0], licenses[1], licenses[2], licenses[3]), installations,
                events(scope, params), documents.summary(scope), recent, period, companies[1], stores[2], licenses[4],
                activity.totals().current(), catalogue(scope, params), daily);
    }

    private Catalogue catalogue(AnalyticsScope scope, MapSqlParameterSource params) {
        return jdbc.queryForObject("""
                select count(*) stores,count(ss.product_count) covered,sum(ss.product_count) products,
                       sum(ss.active_product_count) active_products,min(ss.captured_at) oldest,max(ss.captured_at) latest
                from saas_store s left join lateral (
                    select x.product_count,x.active_product_count,x.captured_at from saas_inventory_snapshot x
                    join saas_installation i on i.id=x.installation_id and i.company_id=x.company_id and i.store_id=x.store_id
                    where x.company_id=s.company_id and x.store_id=s.id and i.active and x.completed_at is not null
                    order by x.captured_at desc,x.snapshot_id desc limit 1
                ) ss on true where true %s
                """.formatted(scope.stores()), params, (row,n) -> new Catalogue(row.getLong("stores"),
                row.getLong("covered"), row.getObject("products",Long.class), row.getObject("active_products",Long.class),
                instant(row,"oldest"), instant(row,"latest")));
    }

    private Events events(AnalyticsScope scope, MapSqlParameterSource params) {
        // One grouping pass across receipts. ROLLUP always yields the grand total, even for an empty table.
        return jdbc.query("""
                select entity_type, count(*) total,
                       count(*) filter (where projection_status='RECEIVED') received,
                       count(*) filter (where projection_status='PROJECTED') projected,
                       count(*) filter (where projection_status='IGNORED') ignored,
                       count(*) filter (where projection_status='ERROR') errors,
                       min(received_at) first_receipt, max(received_at) last_receipt
                from saas_sync_event e where true %s group by rollup(entity_type)
                order by grouping(entity_type), total desc, entity_type
                """.formatted(scope.facts("e")), params, rows -> {
            var types = new ArrayList<Count>();
            while (rows.next()) {
                String type = rows.getString("entity_type");
                if (type == null) {
                    var statuses = List.of(new Count("RECEIVED", rows.getLong("received")),
                            new Count("PROJECTED", rows.getLong("projected")), new Count("IGNORED", rows.getLong("ignored")),
                            new Count("ERROR", rows.getLong("errors")));
                    return new Events(rows.getLong("total"), types, statuses,
                            instant(rows, "first_receipt"), instant(rows, "last_receipt"));
                }
                types.add(new Count(type, rows.getLong("total")));
            }
            throw new IllegalStateException("Missing event aggregate");
        });
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
