package com.tpverp.saas.analytics;

import static com.tpverp.saas.analytics.ReportStatistics.*;
import static com.tpverp.saas.document.CommercialDocumentFacts.UNKNOWN_RELATIONSHIPS;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ReportStatisticsService {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public ReportStatisticsService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Options options() {
        return new Options(jdbc.query("select id,name from saas_company order by lower(name),id", Map.of(),
                (rs, n) -> new CompanyOption(rs.getObject("id", UUID.class), rs.getString("name"))),
                jdbc.query("select id,company_id,name from saas_store order by lower(name),id", Map.of(),
                        (rs, n) -> new StoreOption(rs.getObject("id", UUID.class),
                                rs.getObject("company_id", UUID.class), rs.getString("name"))));
    }

    public ReportStatistics report(Period period, Period other, GroupBy group, UUID company, UUID store, int page, int size) {
        return report(period, other, group, company, store, null, page, size);
    }

    public ReportStatistics report(Period period, Period other, GroupBy group, UUID company, UUID store, String province, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw invalid("page/size");
        return aggregate(period, other, group, new AnalyticsScope(company, store, province), page, size);
    }

    public ReportStatistics export(Period period, Period other, GroupBy group, UUID company, UUID store,
                                   String province, int limit) {
        if (limit < 1 || limit > 50001) throw invalid("limit");
        return aggregate(period, other, group, new AnalyticsScope(company, store, province), 0, limit);
    }

    ReportStatistics aggregate(Period period, Period other, GroupBy group, AnalyticsScope scope, int page, int size) {
        scope.validate(jdbc);
        var params = AnalyticsScope.periods(scope.parameters(), period, other)
                .addValue("size", size).addValue("offset", (long) page * size)
                .addValue("bucketCount", Math.max(bucketCount(group, period), bucketCount(group, other)));
        var coverage = coverage(jdbc, scope, params, other != null);
        return jdbc.query(ReportStatisticsQuery.aggregates(group, scope), params, rs -> {
            boolean complete = coverage.unknownRelationships() == 0;
            var groups = new LinkedHashMap<String, GroupTotals>();
            var totals = new GroupTotals("");
            var types = new TreeMap<String, long[]>();
            var statuses = new LinkedHashMap<String, long[]>();
            for (String status : List.of("RECEIVED", "PROJECTED", "IGNORED", "ERROR")) statuses.put(status, new long[2]);
            long totalGroups = 0;
            while (rs.next()) {
                totalGroups = rs.getLong("total_groups");
                String key = rs.getString("key");
                String label = rs.getString("label");
                GroupTotals target = key.isEmpty() ? totals : groups.computeIfAbsent(key, k -> new GroupTotals(label));
                target.stores = rs.getLong("stores");
                target.licenses = rs.getLong("licenses");
                Integer side = rs.getObject("side", Integer.class);
                if (side == null) continue;
                var sidePeriod = side == 0 ? period : other;
                int days = ReportStatisticsQuery.temporal(group) && !key.isEmpty()
                        ? sidePeriod.bucketDays(group, Integer.parseInt(key)) : sidePeriod.days();
                target.sides[side].add(rs, days, complete);
                String type = rs.getString("entity_type");
                if (key.isEmpty() && type != null) {
                    long events = rs.getLong("events");
                    types.computeIfAbsent(type, ignored -> new long[2])[side] += events;
                    statuses.computeIfAbsent(rs.getString("projection_status"), ignored -> new long[2])[side] += events;
                }
            }
            List<Row> rows = new ArrayList<>();
            groups.forEach((key, aggregate) -> {
                String label = aggregate.label, comparisonLabel = null;
                if (ReportStatisticsQuery.temporal(group)) {
                    int ordinal = Integer.parseInt(key);
                    label = Objects.toString(period.bucketStart(group, ordinal), null);
                    comparisonLabel = other == null ? null : Objects.toString(other.bucketStart(group, ordinal), null);
                }
                var pair = aggregate.result(complete, other != null);
                boolean temporal = ReportStatisticsQuery.temporal(group);
                PeriodMetrics current = temporal && label == null ? null : pair.current();
                PeriodMetrics comparison = temporal && comparisonLabel == null ? null : pair.comparison();
                rows.add(new Row(key, label, comparisonLabel, current, comparison,
                        current != null && comparison != null ? pair.deltas() : List.of()));
            });
            return new ReportStatistics(clock.instant(), period, other, group, page, size, totalGroups, coverage,
                    totals.result(complete, other != null), rows, counts(types, other), counts(statuses, other));
        });
    }

    static Coverage articleCoverage(NamedParameterJdbcTemplate jdbc, AnalyticsScope scope, MapSqlParameterSource params, boolean comparing) {
        // Even a READY document can lack an identity for a product line. That line cannot be ranked reliably.
        String missing = """
                (%s and (d.line_projection_status <> 'READY' or exists (
                    select 1 from saas_commercial_document_line l where l.company_id=d.company_id and l.store_id=d.store_id
                    and l.source_document_id=d.source_document_id and l.line_type='PRODUCT'
                    and l.product_local_id is null and nullif(btrim(l.product_code),'') is null)))
                """.formatted(com.tpverp.saas.document.CommercialDocumentFacts.COUNTS_AS_SALE);
        return coverage(jdbc, scope, params, comparing, missing);
    }

    static Coverage coverage(NamedParameterJdbcTemplate jdbc, AnalyticsScope scope, MapSqlParameterSource params, boolean comparing) {
        return coverage(jdbc, scope, params, comparing, "d.line_projection_status <> 'READY'");
    }

    private static Coverage coverage(NamedParameterJdbcTemplate jdbc, AnalyticsScope scope, MapSqlParameterSource params,
                                     boolean comparing, String missing) {
        return jdbc.queryForObject("""
                select count(*) filter(where unknown_links) unknown_count,
                       count(*) filter(where (unknown_links or missing_lines) and business_date between :from and :to) current_count,
                       count(*) filter(where (unknown_links or missing_lines) and business_date between :otherFrom and :otherTo) other_count
                from (select d.business_date, %s missing_lines, %s unknown_links
                      from saas_commercial_document d where true %s) facts
                """.formatted(missing, UNKNOWN_RELATIONSHIPS, scope.facts("d")), params,
                (rs, n) -> new Coverage(rs.getLong("unknown_count"), rs.getLong("current_count"),
                        comparing ? rs.getLong("other_count") : null));
    }

    private static int bucketCount(GroupBy group, Period period) {
        if (period == null || !ReportStatisticsQuery.temporal(group)) return 0;
        if (group == GroupBy.DAY) return period.days();
        int count = 0;
        while (period.bucketStart(group, count) != null) count++;
        return count;
    }

    private static List<EventCount> counts(Map<String, long[]> counts, Period other) {
        return counts.entrySet().stream().map(e -> new EventCount(e.getKey(), e.getValue()[0],
                other == null ? null : e.getValue()[1])).toList();
    }

    static String percent(BigDecimal difference, BigDecimal previous) {
        return previous.signum() == 0 ? null : difference.movePointRight(2)
                .divide(previous.abs(), 2, RoundingMode.HALF_UP).toPlainString();
    }

    private static final class GroupTotals {
        final String label;
        long stores, licenses;
        final Metrics[] sides = {new Metrics(), new Metrics()};

        GroupTotals(String label) { this.label = label; }

        PeriodPair result(boolean complete, boolean comparing) {
            var current = sides[0].result(complete, stores, licenses);
            var comparison = comparing ? sides[1].result(complete, stores, licenses) : null;
            List<Delta> deltas = new ArrayList<>();
            if (comparing && complete) {
                var amounts = new TreeMap<String, BigDecimal[]>();
                for (int i = 0; i < sides.length; i++) {
                    for (var c : sides[i].currencies) {
                        amounts.computeIfAbsent(c.currency(), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO})[i]
                                = new BigDecimal(c.amount());
                    }
                }
                amounts.forEach((currency, a) -> {
                    BigDecimal diff = a[0].subtract(a[1]);
                    deltas.add(new Delta(currency, diff.toPlainString(), percent(diff, a[1])));
                });
            }
            return new PeriodPair(current, comparison, deltas);
        }
    }

    private static final class Metrics {
        long documents, eligible, returns, cancelled, events, errors, stockMovements;
        final List<CurrencyMetrics> currencies = new ArrayList<>();

        void add(ResultSet rs, int days, boolean complete) throws SQLException {
            long count = rs.getLong("eligible");
            long receipts = rs.getLong("events");
            documents += rs.getLong("documents");
            eligible += count;
            returns += rs.getLong("returns");
            cancelled += rs.getLong("cancelled");
            events += receipts;
            if ("ERROR".equals(rs.getString("projection_status"))) errors += receipts;
            if ("STOCK_MOVEMENT".equals(rs.getString("entity_type"))) stockMovements += receipts;
            if (count > 0) {
                BigDecimal amount = rs.getBigDecimal("amount");
                long stores = rs.getLong("contributing_stores");
                currencies.add(new CurrencyMetrics(rs.getString("currency"), complete ? count : null,
                        complete ? amount.toPlainString() : null, complete ? average(amount, count) : null,
                        complete ? average(amount, days) : null, complete ? stores : null, complete ? average(amount, stores) : null));
            }
        }

        PeriodMetrics result(boolean complete, long stores, long licenses) {
            return new PeriodMetrics(documents, complete ? eligible : null, cancelled, events, errors, stockMovements,
                    currencies, stores, licenses, complete ? eligible - returns : null, complete ? returns : null);
        }

        static String average(BigDecimal amount, long divisor) {
            return amount.divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_UP).toPlainString();
        }
    }
}
