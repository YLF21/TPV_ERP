package com.tpverp.saas.document;

import static com.tpverp.saas.document.CommercialDocumentQuery.*;

import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Internal read facade reused by future authenticated adapters, exports and reports. No new access grant. */
@Service
@Transactional(readOnly = true)
public class CommercialDocumentReadService {
    static final int MAX_AGGREGATE_GROUPS = 2000;
    private final CommercialDocumentReadRepository documents;

    public CommercialDocumentReadService(CommercialDocumentReadRepository documents) { this.documents = documents; }

    public Page page(Scope scope, Filter filter, PageRequest request) {
        requireQuery(scope, filter);
        Objects.requireNonNull(request, "request");
        String fingerprint = CommercialDocumentCursor.fingerprint(scope, filter, request.order());
        var after = CommercialDocumentCursor.decode(request.cursor(), fingerprint, request.order());
        var rows = documents.page(scope, filter, request.order(), after, request.size() + 1);
        boolean more = rows.size() > request.size();
        var items = rows.stream().limit(request.size()).toList();
        String next = more ? CommercialDocumentCursor.from(items.getLast(), fingerprint, request.order()).encode() : null;
        return new Page(items, next, more);
    }

    public Totals documentTotals(Scope scope, Filter filter, Aggregation grouping) {
        requireQuery(scope, filter);
        Objects.requireNonNull(grouping, "grouping");
        var rows = documents.aggregate(scope, filter, grouping, MAX_AGGREGATE_GROUPS + 1);
        if (rows.size() > MAX_AGGREGATE_GROUPS) {
            // Never return a silently truncated monetary report.
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Demasiados grupos documentales: reduce el periodo o los filtros");
        }
        return new Totals(grouping, rows);
    }

    private static void requireQuery(Scope scope, Filter filter) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(filter, "filter");
    }
}
