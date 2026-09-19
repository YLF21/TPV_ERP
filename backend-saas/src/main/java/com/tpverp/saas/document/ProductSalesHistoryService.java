package com.tpverp.saas.document;

import static com.tpverp.saas.document.ProductSalesHistoryApi.*;
import static com.tpverp.saas.document.ProductSalesHistoryQuery.*;

import com.tpverp.saas.document.CommercialDocumentQuery.Scope;
import com.tpverp.saas.license.InstallationAuthenticator;
import com.tpverp.saas.license.SaasInstallationRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Existing F6 operator permissions remain enforced by the local backend, before this installation read. */
@Service
public class ProductSalesHistoryService {
    private final SaasInstallationRepository installations;
    private final InstallationAuthenticator authenticator;
    private final ProductSalesHistoryRepository repository;
    public ProductSalesHistoryService(SaasInstallationRepository installations, InstallationAuthenticator authenticator,
            ProductSalesHistoryRepository repository) {
        this.installations = installations;
        this.authenticator = authenticator;
        this.repository = repository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Response page(Request request, String token) {
        Context context = context(request, token);
        int size = request.size() == null ? 200 : request.size();
        if (size < 1 || size > 200) throw invalid("size");
        String fingerprint = ProductSalesHistoryCursor.fingerprint(context.scope(), context.filter(), context.order());
        var cursor = ProductSalesHistoryCursor.decode(request.cursor(), fingerprint, context.order());
        List<Item> rows = repository.page(context.scope(), context.filter(), context.order(), cursor, size + 1);
        boolean more = rows.size() > size;
        List<Item> items = more ? List.copyOf(rows.subList(0, size)) : rows;
        String next = more ? ProductSalesHistoryCursor.from(items.getLast(), fingerprint, context.order()).encode() : null;
        return response(context, items, next, more);
    }

    /** Every batch and aggregate shares one snapshot; over-limit exports fail, never silently truncate. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Response export(Request request, String token) {
        Context context = context(request, token);
        if (request.cursor() != null) throw invalid("export cursor");
        if ("comparison".equals(request.view())) return response(context, List.of(), null, false);
        if (request.view() != null && !"detail".equals(request.view())) throw invalid("view");
        var items = new ArrayList<Item>();
        var fingerprint = ProductSalesHistoryCursor.fingerprint(context.scope(), context.filter(), context.order());
        ProductSalesHistoryCursor cursor = null;
        while (true) {
            List<Item> batch = repository.page(context.scope(), context.filter(), context.order(), cursor, 201);
            boolean more = batch.size() > 200;
            int count = Math.min(200, batch.size());
            if (items.size() + count > 50_000 || items.size() + count == 50_000 && more) {
                throw limit("PRODUCT_HISTORY_EXPORT_LIMIT_EXCEEDED");
            }
            items.addAll(batch.subList(0, count));
            if (!more) return response(context, List.copyOf(items), null, false);
            cursor = ProductSalesHistoryCursor.from(items.getLast(), fingerprint, context.order());
        }
    }

    private Response response(Context context, List<Item> items, String cursor, boolean more) {
        var coverage = repository.coverage(context.scope(), context.filter());
        return new Response(context.scope().companyId(), context.filter().productCode(), "RECEIVED_IN_SAAS", items,
                repository.stores(context.scope()), repository.totals(context.scope(), context.filter()),
                repository.comparison(context.scope(), context.filter()), cursor, more,
                coverage.incompleteDocuments(), coverage.receivedAt());
    }
    private Context context(Request request, String token) {
        if (request == null || request.companyId() == null || request.storeId() == null) throw invalid("context");
        var installation = authenticator.requireLinkedInstallation(request.companyId(), request.storeId(),
                installations.findByCompany_IdAndStore_Id(request.companyId(), request.storeId()), token);
        Set<java.util.UUID> stores = Set.of();
        if (request.storeIds() != null) {
            if (request.storeIds().size() > 2000 || request.storeIds().stream().anyMatch(java.util.Objects::isNull)) throw invalid("storeIds");
            stores = new HashSet<>(request.storeIds());
        }
        return new Context(Scope.company(installation.getCompany().getId()),
                new Filter(request.productCode(), request.from(), request.to(), request.status(), stores),
                Order.parse(request.sortBy(), request.sortDirection()));
    }
    static QueryFailure limit(String code) { return new QueryFailure(code); }
    public static class QueryFailure extends ResponseStatusException {
        private final String code;
        QueryFailure(String code) { super(HttpStatus.PAYLOAD_TOO_LARGE, "Product sales history result exceeds its safe limit"); this.code = code; }
        public String getCode() { return code; }
    }
    private record Context(Scope scope, Filter filter, Order order) { }
}
