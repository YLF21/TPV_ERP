package com.tpverp.saas.analytics;

import static com.tpverp.saas.analytics.DocumentReport.*;
import static com.tpverp.saas.analytics.ReportStatistics.Period;
import static com.tpverp.saas.analytics.ReportStatistics.invalid;
import static com.tpverp.saas.document.CommercialDocumentFacts.COUNTS_AS_SALE;
import static com.tpverp.saas.document.CommercialDocumentFacts.UNKNOWN_RELATIONSHIPS;

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
public class DocumentReportService {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public DocumentReportService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public DocumentReport report(Period period, UUID company, UUID store, String province, UUID productId,
                                 String productCode, Sort sort, Direction direction, int page, int size) {
        return report(period, company, store, province, productId, productCode, null, sort, direction, page, size);
    }

    public DocumentReport report(Period period, UUID company, UUID store, String province, UUID productId,
                                 String productCode, String currency, Sort sort, Direction direction, int page, int size) {
        if (size < 1 || size > 100 || page < 0) throw invalid("page/size");
        return read(period, company, store, province, productId, productCode, currency, sort, direction, page, size);
    }

    public DocumentReport export(Period period, UUID company, UUID store, String province, UUID productId,
                                 String productCode, Sort sort, Direction direction, int limit) {
        return export(period, company, store, province, productId, productCode, null, sort, direction, limit);
    }

    public DocumentReport export(Period period, UUID company, UUID store, String province, UUID productId,
                                 String productCode, String currency, Sort sort, Direction direction, int limit) {
        if (limit < 1 || limit > 50001) throw invalid("limit");
        return read(period, company, store, province, productId, productCode, currency, sort, direction, 0, limit);
    }

    private DocumentReport read(Period period, UUID company, UUID store, String province, UUID productId,
                                String productCode, String currency, Sort sort, Direction direction, int page, int size) {
        if (productCode != null && (productId != null || productCode.isBlank()
                || productCode.codePointCount(0,productCode.length()) > 128)) throw invalid("productCode");
        if (currency != null && !currency.matches("[A-Z]{3}")) throw invalid("currency");
        var scope = new AnalyticsScope(company, store, province);
        scope.validate(jdbc);
        var params = AnalyticsScope.periods(scope.parameters(), period, null).addValue("limit", size)
                .addValue("offset", (long) page * size);
        var coverage = ReportStatisticsService.coverage(jdbc, scope, params, false);
        params.addValue("complete", coverage.unknownRelationships() == 0);
        String currencyFilter = "";
        if (currency != null) {
            params.addValue("currency", currency);
            currencyFilter = " and d.currency=:currency";
        }
        String productFilter = "";
        if (productId != null || productCode != null) {
            params.addValue("productId", productId).addValue("productCode", productCode);
            String match = productId != null ? "l.product_local_id=:productId"
                    : "l.product_local_id is null and l.product_code=:productCode";
            productFilter = """
                     and d.line_projection_status='READY' and exists(select 1 from saas_commercial_document_line l
                        where l.company_id=d.company_id and l.store_id=d.store_id and l.source_document_id=d.source_document_id
                          and l.line_type='PRODUCT' and %s)
                    """.formatted(match);
        }
        Sort field = sort == null ? Sort.DATE : sort;
        Direction order = direction == null ? (field == Sort.DATE || field == Sort.AMOUNT ? Direction.DESC : Direction.ASC) : direction;
        String ordering = switch (field) {
            case DATE -> "d.business_date";
            case NUMBER -> "d.document_number collate \"C\"";
            case CURRENCY -> "d.currency";
            case AMOUNT -> "d.currency asc,d.total";
        } + " " + order.name() + ",d.company_id,d.store_id,d.source_document_id";
        // Select only the current headers in the requested slice. Relation checks still see the full
        // store history and the scope-wide coverage survives narrowing to a currency or product.
        String query = """
                with source as (
                    select d.company_id,d.store_id,d.source_document_id,d.currency,d.total,d.document_number,
                           d.document_type,d.document_status,d.business_date,d.line_projection_status,d.relationships_complete
                    from saas_commercial_document d where d.business_date between :from and :to %s %s %s
                ), page as (
                    select d.* from source d order by %s limit :limit offset :offset
                )
                select count_source.total_rows,d.*,c.name company_name,s.name store_name,
                       case when not %s then false when :complete then true end eligible,
                       not %s known_relationships
                from (select count(*) total_rows from source) count_source
                left join page d on true left join saas_company c on c.id=d.company_id
                left join saas_store s on s.id=d.store_id and s.company_id=d.company_id
                order by %s
                """;
        // UNKNOWN_RELATIONSHIPS also inspects the source projection's completeness flag.
        query = query.formatted(scope.facts("d"), currencyFilter, productFilter, ordering,
                COUNTS_AS_SALE, UNKNOWN_RELATIONSHIPS, ordering);
        return jdbc.query(query, params, rs -> {
            long count = 0;
            List<Row> rows = new ArrayList<>();
            while (rs.next()) {
                count = rs.getLong("total_rows");
                UUID id = rs.getObject("source_document_id", UUID.class);
                if (id == null) continue;
                rows.add(new Row(id,rs.getString("document_type"),rs.getString("document_number"),
                        rs.getString("document_status"),rs.getDate("business_date").toLocalDate(),
                        rs.getObject("company_id",UUID.class),rs.getString("company_name"),rs.getObject("store_id",UUID.class),
                        rs.getString("store_name"),rs.getString("currency"),rs.getBigDecimal("total").toPlainString(),
                        rs.getObject("eligible",Boolean.class),"READY".equals(rs.getString("line_projection_status")),
                        rs.getBoolean("known_relationships")));
            }
            return new DocumentReport(clock.instant(),period,page,size,count,coverage,rows);
        });
    }
}
