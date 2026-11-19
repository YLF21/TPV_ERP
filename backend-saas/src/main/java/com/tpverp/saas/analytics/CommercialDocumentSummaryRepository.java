package com.tpverp.saas.analytics;

import com.tpverp.saas.analytics.AdminSummary.Amount;
import com.tpverp.saas.analytics.AdminSummary.Count;
import com.tpverp.saas.analytics.AdminSummary.Documents;
import com.tpverp.saas.document.CommercialDocumentFacts;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CommercialDocumentSummaryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public CommercialDocumentSummaryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Documents summary() {
        return summary(new AnalyticsScope(null, null, null));
    }

    Documents summary(AnalyticsScope scope) {
        var params = scope.parameters();
        var coverage = jdbc.queryForObject("""
                select count(*) total, count(distinct store_id) stores, count(*) filter (where unknown_links) unknown_links,
                       count(*) filter (where line_projection_status <> 'READY' or unknown_links) incomplete
                from (select d.store_id, d.line_projection_status, %s as unknown_links from saas_commercial_document d
                      where true %s) coverage
                """.formatted(CommercialDocumentFacts.UNKNOWN_RELATIONSHIPS, scope.facts("d")), params, (row, n) -> new Coverage(row.getLong("total"),
                row.getLong("stores"), row.getLong("unknown_links"), row.getLong("incomplete")));
        var types = new ArrayList<Count>();
        var statuses = new ArrayList<Count>();
        jdbc.query("""
                select document_type, document_status, count(*) total from saas_commercial_document d where true %s
                group by grouping sets ((document_type), (document_status))
                order by total desc, document_type, document_status
                """.formatted(scope.facts("d")), params, row -> {
            String type = row.getString("document_type");
            if (type == null) {
                statuses.add(new Count(row.getString("document_status"), row.getLong("total")));
            } else {
                types.add(new Count(type, row.getLong("total")));
            }
        });
        long products = jdbc.queryForObject("""
                select count(distinct (l.company_id, l.product_code))
                from saas_commercial_document_line l join saas_commercial_document d
                    using (company_id, store_id, source_document_id)
                where d.line_projection_status='READY' and l.line_type='PRODUCT'
                  and l.product_code is not null and btrim(l.product_code)<>'' %s
                """.formatted(scope.facts("d")), params, Long.class);
        // Unknown links can hide a conversion. Suppress the entire monetary aggregate in that case.
        var amounts = coverage.unknownLinks() == 0 ? amounts(scope) : List.<Amount>of();
        return new Documents(coverage.total(), types, statuses, amounts, coverage.stores(), coverage.incomplete(),
                coverage.unknownLinks(), products);
    }

    private List<Amount> amounts(AnalyticsScope scope) {
        return jdbc.query("""
                select d.currency, count(*) documents, sum(d.total) amount from saas_commercial_document d
                where %s %s group by d.currency order by d.currency
                """.formatted(CommercialDocumentFacts.COUNTS_AS_SALE, scope.facts("d")), scope.parameters(), (row, n) -> new Amount(row.getString("currency"),
                row.getLong("documents"), row.getBigDecimal("amount").toPlainString()));
    }

    private record Coverage(long total, long stores, long unknownLinks, long incomplete) {}
}
