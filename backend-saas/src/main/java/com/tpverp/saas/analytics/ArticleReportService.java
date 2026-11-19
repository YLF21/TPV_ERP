package com.tpverp.saas.analytics;

import static com.tpverp.saas.analytics.ArticleReport.*;
import static com.tpverp.saas.analytics.ReportStatistics.Period;
import static com.tpverp.saas.analytics.ReportStatistics.invalid;
import static com.tpverp.saas.document.CommercialDocumentFacts.COUNTS_AS_SALE;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ArticleReportService {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public ArticleReportService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public ArticleReport report(Period period, Period other, UUID company, UUID store, String province,
                                String search, Sort sort, int page, int size) {
        return report(period, other, company, store, province, search, sort, null, page, size);
    }

    public ArticleReport report(Period period, Period other, UUID company, UUID store, String province,
                                String search, Sort sort, Direction direction, int page, int size) {
        if (size < 1 || size > 100) throw invalid("size");
        return read(period, other, company, store, province, search, sort, direction, page, size);
    }

    /** Export takes one bounded database snapshot and includes the unpaged row count for overflow detection. */
    public ArticleReport export(Period period, Period other, UUID company, UUID store, String province,
                                String search, Sort sort, Direction direction, int limit) {
        if (limit < 1 || limit > 50001) throw invalid("limit");
        return read(period, other, company, store, province, search, sort, direction, 0, limit);
    }

    private ArticleReport read(Period period, Period other, UUID company, UUID store, String province,
                               String search, Sort sort, Direction direction, int page, int size) {
        if (page < 0) throw invalid("page");
        if (search != null && search.length() > 256) throw invalid("search");
        var scope = new AnalyticsScope(company, store, province);
        scope.validate(jdbc);
        var params = AnalyticsScope.periods(scope.parameters(), period, other)
                .addValue("size", size).addValue("offset", (long) page * size);
        var coverage = ReportStatisticsService.articleCoverage(jdbc, scope, params, other != null);
        if (coverage.unknownRelationships() != 0 || coverage.currentIncompleteDocuments() != 0
                || (other != null && coverage.comparisonIncompleteDocuments() != 0)) {
            return new ArticleReport(clock.instant(), period, other, page, size, 0, coverage, List.of());
        }
        String lookup = "";
        if (search != null && !search.isBlank()) {
            params.addValue("search", search.strip());
            lookup = " where strpos(lower(coalesce(k.product_code,'')), lower(:search))>0"
                    + " or strpos(lower(coalesce(k.product_name,'')), lower(:search))>0";
        }
        String metric = switch (sort) {
            case AMOUNT -> "current_net_amount";
            case QUANTITY -> "current_net_quantity";
            case RETURNS -> "current_quantity_returned";
            case CODE -> "product_code collate \"C\"";
        };
        var ordering = direction != null ? direction : (sort == Sort.CODE ? Direction.ASC : Direction.DESC);
        // Currency is always a partition boundary for monetary rankings. Exact historical code is the
        // fallback identity only when the source supplied no local product UUID.
        String query = """
                with facts as (
                    select d.company_id,d.store_id,d.currency,d.business_date,d.source_document_id,l.line_position,
                           coalesce('id:'||l.product_local_id::text,'code:'||l.product_code) identity,
                           l.product_local_id,l.product_code,l.product_name,
                           case when d.document_type='RECTIFICATIVA_VENTA' and l.price_tariff='DIFERENCIA'
                                then 0 else l.quantity end quantity,l.line_total,
                           d.business_date between :from and :to current_period,
                           d.business_date between :otherFrom and :otherTo comparison_period
                    from saas_commercial_document d join saas_commercial_document_line l using(company_id,store_id,source_document_id)
                    where ((d.business_date between :from and :to) or (d.business_date between :otherFrom and :otherTo))
                      and d.line_projection_status='READY' and l.line_type='PRODUCT' and %s %s
                ), labels as (
                    select distinct on(company_id,store_id,currency,identity) company_id,store_id,currency,identity,
                           product_local_id,product_code,product_name from facts
                    order by company_id,store_id,currency,identity,business_date desc,source_document_id desc,line_position desc
                ), amounts as (
                    select company_id,store_id,currency,identity,%s,%s from facts group by company_id,store_id,currency,identity
                ), contributions as (
                    select a.*,sum(current_net_amount) over(partition by currency) current_currency_amount,
                           sum(comparison_net_amount) over(partition by currency) comparison_currency_amount
                    from amounts a
                ), items as (
                    select a.*,k.product_local_id,k.product_code,k.product_name from contributions a
                    join labels k using(company_id,store_id,currency,identity) %s
                ), ranked as (
                    select items.*,dense_rank() over(partition by currency order by %s %s nulls last) as rank
                    from items
                ), page as (
                    select * from ranked order by currency,rank,company_id,store_id,identity limit :size offset :offset
                )
                select total_rows,p.*,c.name company_name,s.name store_name from (select count(*) total_rows from items) total
                left join page p on true left join saas_company c on c.id=p.company_id
                left join saas_store s on s.id=p.store_id and s.company_id=p.company_id
                order by p.currency,p.rank,p.company_id,p.store_id,p.identity
                """.formatted(COUNTS_AS_SALE, scope.facts("d"), side("current"), side("comparison"), lookup, metric, ordering.name());
        return jdbc.query(query, params, rs -> {
            List<Row> rows = new ArrayList<>();
            long count = 0;
            while (rs.next()) {
                count = rs.getLong("total_rows");
                if (rs.getObject("company_id") == null) continue;
                var current = metrics(rs, "current");
                var prior = other == null ? null : metrics(rs, "comparison");
                BigDecimal delta = prior == null ? null : decimal(current.netAmount()).subtract(decimal(prior.netAmount()));
                rows.add(new Row(rs.getLong("rank"), rs.getObject("company_id",UUID.class), rs.getString("company_name"),
                        rs.getObject("store_id",UUID.class), rs.getString("store_name"), rs.getObject("product_local_id",UUID.class),
                        rs.getString("product_code"), rs.getString("product_name"), rs.getString("currency"), current, prior,
                        delta == null ? null : delta.toPlainString(), prior == null ? null : ReportStatisticsService.percent(delta, decimal(prior.netAmount())),
                        prior == null ? null : decimal(current.netQuantity()).subtract(decimal(prior.netQuantity())).toPlainString()));
            }
            return new ArticleReport(clock.instant(),period,other,page,size,count,coverage,rows);
        });
    }

    private static String side(String prefix) {
        return """
                coalesce(sum(greatest(quantity,0)) filter(where %1$s_period),0) %1$s_quantity_sold,
                coalesce(sum(greatest(-quantity,0)) filter(where %1$s_period),0) %1$s_quantity_returned,
                coalesce(sum(quantity) filter(where %1$s_period),0) %1$s_net_quantity,
                coalesce(sum(greatest(line_total,0)) filter(where %1$s_period),0) %1$s_sales_amount,
                coalesce(sum(greatest(-line_total,0)) filter(where %1$s_period),0) %1$s_returns_amount,
                coalesce(sum(line_total) filter(where %1$s_period),0) %1$s_net_amount,
                count(distinct source_document_id) filter(where %1$s_period and quantity>0) %1$s_sale_documents
                """.formatted(prefix);
    }

    private static BigDecimal decimal(String value) { return new BigDecimal(value); }

    private static Metrics metrics(ResultSet rs, String side) throws SQLException {
        long saleDocuments = rs.getLong(side + "_sale_documents");
        BigDecimal sold = rs.getBigDecimal(side + "_quantity_sold");
        BigDecimal net = rs.getBigDecimal(side + "_net_amount");
        BigDecimal total = rs.getBigDecimal(side + "_currency_amount");
        return new Metrics(sold.toPlainString(),
                rs.getBigDecimal(side + "_quantity_returned").toPlainString(), rs.getBigDecimal(side + "_net_quantity").toPlainString(),
                rs.getBigDecimal(side + "_sales_amount").toPlainString(), rs.getBigDecimal(side + "_returns_amount").toPlainString(),
                net.toPlainString(), saleDocuments, saleDocuments == 0 ? null
                        : sold.divide(BigDecimal.valueOf(saleDocuments),3,RoundingMode.HALF_UP).toPlainString(),
                total.signum() == 0 ? null : net.movePointRight(2).divide(total,2,RoundingMode.HALF_UP).toPlainString());
    }
}
