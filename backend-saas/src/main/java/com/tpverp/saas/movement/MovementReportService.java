package com.tpverp.saas.movement;

import static com.tpverp.saas.movement.MovementReport.*;

import com.tpverp.saas.analytics.AnalyticsScope;
import com.tpverp.saas.analytics.ReportStatistics.Period;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.ArrayList;
import java.util.UUID;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MovementReportService {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public MovementReportService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private MapSqlParameterSource params(
            AnalyticsScope scope,
            Filter filter,
            Period period,
            Period comparison,
            int page,
            int size,
            int maximumSize) {
        if (page < 0 || size < 1 || size > maximumSize)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paginación no válida");
        scope.validate(jdbc);
        return AnalyticsScope.periods(scope.parameters(), period, comparison)
                .addValue("warehouse", filter.warehouseId())
                .addValue("product", filter.productId())
                .addValue("type", filter.type() == null ? null : filter.type().name())
                .addValue("size", size)
                .addValue("offset", (long) page * size)
                .addValue(
                        "days",
                        Math.max(period.days(), comparison == null ? 0 : comparison.days()));
    }

    public Summary summary(
            Filter filter, Period period, Period comparison, GroupBy groupBy, int page, int size) {
        return readSummary(filter, period, comparison, groupBy, page, size, 200);
    }

    public Summary exportSummary(
            Filter filter, Period period, Period comparison, GroupBy groupBy, int limit) {
        return readSummary(filter, period, comparison, groupBy, 0, limit, 50_001);
    }

    private Summary readSummary(
            Filter filter,
            Period period,
            Period comparison,
            GroupBy groupBy,
            int page,
            int size,
            int maximumSize) {
        var scope = new AnalyticsScope(filter.companyId(), filter.storeId(), filter.province());
        var params = params(scope, filter, period, comparison, page, size, maximumSize);
        return jdbc.query(
                MovementQuery.facts(scope, filter) + MovementQuery.summary(groupBy),
                params,
                rs -> {
                    // Totals and coverage also contribute a row when the requested page is empty.
                    rs.next();
                    long total = rs.getLong("total");
                    long currentCount = rs.getLong("current_count");
                    Long otherCount = comparison == null ? null : rs.getLong("other_count");
                    var coverage =
                            new Coverage(
                                    rs.getLong("malformed"),
                                    rs.getLong("conflicts"),
                                    rs.getLong("duplicates"));
                    var rows = new ArrayList<Bucket>();
                    do {
                        String key = rs.getString("key");
                        if (key == null) break;
                        String label = rs.getString("label");
                        String comparisonLabel = null;
                        if (groupBy == GroupBy.DAY) {
                            int index = Integer.parseInt(key);
                            label =
                                    index < period.days()
                                            ? period.from().plusDays(index).toString()
                                            : null;
                            if (comparison != null && index < comparison.days())
                                comparisonLabel = comparison.from().plusDays(index).toString();
                        }
                        rows.add(
                                new Bucket(
                                        key,
                                        label,
                                        comparisonLabel,
                                        groupBy == GroupBy.DAY && label == null ? null : rs.getLong("cnt"),
                                        comparison == null || groupBy == GroupBy.DAY && comparisonLabel == null
                                                ? null : rs.getLong("other_cnt")));
                    } while (rs.next());
                    return new Summary(
                            clock.instant(),
                            period,
                            comparison,
                            page,
                            size,
                            total,
                            groupBy,
                            currentCount,
                            otherCount,
                            coverage,
                            rows);
                });
    }

    public Ledger ledger(
            Filter filter, Period period, Sort sort, Direction direction, int page, int size) {
        return readLedger(filter, period, sort, direction, page, size, 200);
    }

    public Ledger exportLedger(
            Filter filter, Period period, Sort sort, Direction direction, int limit) {
        return readLedger(filter, period, sort, direction, 0, limit, 50_001);
    }

    private Ledger readLedger(
            Filter filter,
            Period period,
            Sort sort,
            Direction direction,
            int page,
            int size,
            int maximumSize) {
        var scope = new AnalyticsScope(filter.companyId(), filter.storeId(), filter.province());
        var params = params(scope, filter, period, null, page, size, maximumSize);
        return jdbc.query(
                MovementQuery.facts(scope, filter) + MovementQuery.ledger(sort, direction),
                params,
                rs -> {
                    rs.next();
                    long total = rs.getLong("total");
                    var rows = new ArrayList<Movement>();
                    do {
                        UUID id = rs.getObject("entity_id", UUID.class);
                        if (id == null) break;
                        rows.add(
                                new Movement(
                                        id,
                                        rs.getObject("company_id", UUID.class),
                                        rs.getString("company_name"),
                                        rs.getObject("store_id", UUID.class),
                                        rs.getString("store_name"),
                                        rs.getString("province"),
                                        rs.getObject("warehouse_id", UUID.class),
                                        rs.getString("warehouse_name"),
                                        rs.getObject("product_id", UUID.class),
                                        rs.getString("product_name"),
                                        rs.getString("type"),
                                        rs.getBigDecimal("quantity").toPlainString(),
                                        rs.getTimestamp("occurred_at").toInstant(),
                                        rs.getTimestamp("received_at").toInstant()));
                    } while (rs.next());
                    return new Ledger(page, size, total, rows);
                });
    }
}
