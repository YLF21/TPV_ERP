package com.tpverp.saas.document;

import static com.tpverp.saas.document.CommercialDocumentApi.*;
import static com.tpverp.saas.document.CommercialDocumentQuery.invalid;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.document.CommercialDocumentQuery.*;
import com.tpverp.saas.license.InstallationAuthenticator;
import com.tpverp.saas.license.SaasInstallationRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Company-wide M2M reads; caller-side operator permission checks remain in the local backend. */
@Service
public class CommercialDocumentQueryService {
    private static final int MAX_EXPORT_ROWS = 50_000;
    private static final int BATCH_SIZE = 200;
    private static final TypeReference<Map<String, String>> ADDRESS_TYPE = new TypeReference<>() { };
    private final SaasInstallationRepository installations;
    private final InstallationAuthenticator authenticator;
    private final CommercialDocumentReadService reads;
    private final CommercialDocumentReadRepository documents;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CommercialDocumentQueryService(SaasInstallationRepository installations,
            InstallationAuthenticator authenticator, CommercialDocumentReadService reads,
            CommercialDocumentReadRepository documents, NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.installations = installations;
        this.authenticator = authenticator;
        this.reads = reads;
        this.documents = documents;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PageResponse page(CommercialDocumentApi.PageRequest request, String token) {
        var context = authenticate(request, token);
        var page = reads.page(context.scope(), filter(request.reportKey(), request.filters(), context.customer().id()),
                new CommercialDocumentQuery.PageRequest(order(request.sortBy(), request.sortDirection()),
                        request.size(), request.cursor()));
        return new PageResponse(context.scope().companyId(), context.customer(), page.items().stream()
                .map(CommercialDocumentQueryService::view).toList(), page.nextCursor(), page.hasMore(), COVERAGE);
    }

    /** All pages and selected-key batches share ONE database snapshot, including the verified customer profile. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExportResponse export(ExportRequest request, String token) {
        var context = authenticate(request, token);
        var filter = filter(request.reportKey(), request.filters(), context.customer().id());
        var order = order(request.sortBy(), request.sortDirection());
        boolean filtered = request.filters() != null && request.filters().active();
        if (filtered == (request.documentKeys() != null)) throw invalid("documentKeys/filters");
        List<Row> rows = filtered ? filteredRows(context.scope(), filter, order)
                : selectedRows(context.scope(), filter, request.documentKeys());
        var totals = new TreeMap<String, Amounts>();
        rows.forEach(row -> totals.computeIfAbsent(row.currency(), ignored -> new Amounts()).add(row));
        return new ExportResponse(context.scope().companyId(), context.customer(),
                rows.stream().map(CommercialDocumentQueryService::view).toList(),
                totals.entrySet().stream().map(entry -> new CurrencyTotal(entry.getKey(), entry.getValue().count,
                        money(entry.getValue().subtotal), money(entry.getValue().tax), money(entry.getValue().total))).toList(), COVERAGE);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AnnualResponse annual(AnnualRequest request, String token) {
        var context = authenticate(request, token);
        if (request.year() < 1 || request.year() > 9998) throw invalid("year");
        var filter = new Filter(Set.of(), context.customer().id(), Set.of(Type.FACTURA_VENTA, Type.RECTIFICATIVA_VENTA),
                Set.of(Status.CONFIRMADO, Status.PENDIENTE, Status.PARCIAL, Status.PAGADO),
                LocalDate.of(request.year(), 1, 1), LocalDate.of(request.year(), 12, 31), null, null);
        var groups = reads.documentTotals(context.scope(), filter, new Aggregation(Period.QUARTER, Set.of())).items();
        var currencies = new TreeMap<String, Map<Integer, Amounts>>();
        for (var total : groups) {
            int quarter = (total.group().periodStart().getMonthValue() - 1) / 3 + 1;
            currencies.computeIfAbsent(total.group().currency(), ignored -> new HashMap<>())
                    .computeIfAbsent(quarter, ignored -> new Amounts()).add(total);
        }
        var quarters = new ArrayList<Quarter>();
        var totals = new ArrayList<AnnualTotal>();
        currencies.forEach((currency, amounts) -> {
            var annual = new Amounts();
            for (int number = 1; number <= 4; number++) {
                var current = amounts.getOrDefault(number, new Amounts());
                quarters.add(new Quarter(number, currency, current.count, money(current.total)));
                annual.count += current.count;
                annual.total = annual.total.add(current.total);
            }
            totals.add(new AnnualTotal(currency, annual.count, money(annual.total)));
        });
        return new AnnualResponse(context.scope().companyId(), context.customer(), context.issuer(), request.year(),
                quarters, totals, COVERAGE);
    }

    private List<Row> filteredRows(Scope scope, Filter filter, Order order) {
        var rows = new ArrayList<Row>();
        String cursor = null;
        do {
            var page = reads.page(scope, filter, new CommercialDocumentQuery.PageRequest(order, BATCH_SIZE, cursor));
            if (rows.size() + page.items().size() > MAX_EXPORT_ROWS
                    || rows.size() + page.items().size() == MAX_EXPORT_ROWS && page.hasMore()) throw exportLimit();
            rows.addAll(page.items());
            if (!page.hasMore()) return rows;
            if (page.items().isEmpty() || page.nextCursor() == null || page.nextCursor().equals(cursor)) {
                throw new IllegalStateException("Document export cursor did not advance");
            }
            cursor = page.nextCursor();
        } while (true);
    }

    private List<Row> selectedRows(Scope scope, Filter filter, List<DocumentKey> keys) {
        if (keys == null) throw invalid("documentKeys");
        if (keys.size() > MAX_EXPORT_ROWS) throw exportLimit();
        if (keys.stream().anyMatch(key -> key == null || key.storeId() == null || key.documentId() == null)
                || new HashSet<>(keys).size() != keys.size()) throw invalid("documentKeys");
        var result = new ArrayList<Row>(keys.size());
        for (int start = 0; start < keys.size(); start += BATCH_SIZE) {
            var batch = keys.subList(start, Math.min(start + BATCH_SIZE, keys.size()));
            var found = new HashMap<DocumentKey, Row>();
            documents.selected(scope, filter, batch).forEach(row -> found.put(new DocumentKey(row.storeId(), row.documentId()), row));
            if (found.size() != batch.size() || !found.keySet().containsAll(batch)) {
                throw new QueryFailure(HttpStatus.CONFLICT, "CUSTOMER_DOCUMENT_SELECTION_UNAVAILABLE",
                        "La seleccion documental no esta disponible para este cliente");
            }
            batch.forEach(key -> result.add(found.get(key)));
        }
        return result;
    }

    private Context authenticate(CustomerContext request, String token) {
        if (request == null || request.companyId() == null || request.storeId() == null
                || request.localCustomerId() == null || request.expectedCustomerId() == null) throw invalid("context");
        var installation = authenticator.requireLinkedInstallation(request.companyId(), request.storeId(),
                installations.findByCompany_IdAndStore_Id(request.companyId(), request.storeId()), token);
        UUID companyId = installation.getCompany().getId();
        var customers = jdbc.query("""
                select c.id, c.code, c.name, c.tax_id, c.address_json::text as address_json
                  from saas_customer_identity_link l
                  join saas_erp_customer c on c.id = l.customer_id and c.company_id = l.company_id
                 where l.company_id = :companyId and l.installation_id = :installationId
                   and l.local_customer_id = :localCustomerId and c.id = :customerId
                """, new MapSqlParameterSource().addValue("companyId", companyId)
                        .addValue("installationId", installation.getId()).addValue("localCustomerId", request.localCustomerId())
                        .addValue("customerId", request.expectedCustomerId()),
                (row, index) -> new CustomerProfile(row.getObject("id", UUID.class), row.getString("code"),
                        row.getString("name"), row.getString("tax_id"), addressJson(row.getString("address_json"))));
        if (customers.size() != 1) throw new QueryFailure(HttpStatus.CONFLICT, "SAAS_CUSTOMER_BINDING_REQUIRED",
                "El cliente local no tiene el vinculo central esperado");
        var company = installation.getCompany();
        return new Context(Scope.company(companyId), customers.getFirst(),
                new IssuerProfile(companyId, company.getName(), company.getTaxId(), address(company.getCompanyAddress())));
    }

    static Filter filter(String reportKey, Filters filters, UUID customerId) {
        if (reportKey == null) throw invalid("reportKey");
        Set<Type> types = switch (reportKey) {
            case "tickets" -> Set.of(Type.TICKET);
            case "invoices" -> Set.of(Type.FACTURA_VENTA, Type.RECTIFICATIVA_VENTA);
            case "delivery-notes" -> Set.of(Type.ALBARAN_VENTA);
            default -> throw invalid("reportKey");
        };
        Set<Status> statuses = Set.of(Status.values());
        if (filters != null && filters.status() != null && !filters.status().isBlank()) {
            try { statuses = Set.of(Status.valueOf(filters.status().strip())); }
            catch (IllegalArgumentException exception) { throw invalid("status"); }
        }
        return new Filter(Set.of(), customerId, types, statuses, filters == null ? null : filters.dateFrom(),
                filters == null ? null : filters.dateTo(), null, filters == null ? null : filters.search());
    }

    static Order order(String sortBy, String direction) {
        SortField field = sortBy == null ? SortField.DATE : switch (sortBy.strip().toLowerCase(Locale.ROOT)) {
            case "number" -> SortField.NUMBER; case "date" -> SortField.DATE;
            case "type" -> SortField.TYPE; case "status" -> SortField.STATUS;
            case "base" -> SortField.BASE; case "tax" -> SortField.TAX;
            case "total" -> SortField.TOTAL; case "terminal" -> SortField.TERMINAL;
            case "user" -> SortField.USER; case "store" -> SortField.STORE;
            case "currency" -> SortField.CURRENCY; default -> throw invalid("sortBy");
        };
        try {
            return new Order(field, direction == null ? Direction.DESC : Direction.valueOf(direction.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) { throw invalid("sortDirection"); }
    }

    private static DocumentRow view(Row row) {
        return new DocumentRow(row.storeId() + "/" + row.documentId(), row.storeId(), row.storeCode(), row.documentId(),
                row.installationId(), row.sourceRevision(), row.customerId(), row.type().name(), row.status().name(),
                row.number(), row.date(), row.currency(), money(row.subtotal()), money(row.taxTotal()), money(row.total()),
                row.terminalName(), row.userName());
    }

    private String addressJson(String value) {
        if (value == null) return "";
        try { return address(mapper.readValue(value, ADDRESS_TYPE)); }
        catch (java.io.IOException exception) { throw new IllegalStateException("Invalid central customer address", exception); }
    }

    private static String address(Map<String, String> value) {
        if (value == null) return "";
        var parts = new ArrayList<String>();
        for (var aliases : List.of(List.of("address", "line1", "linea1"), List.of("line2", "linea2"),
                List.of("postalCode", "codigoPostal"), List.of("city", "ciudad"),
                List.of("province", "provincia"), List.of("country", "pais"))) {
            aliases.stream().map(value::get).filter(part -> part != null && !part.isBlank())
                    .findFirst().map(String::strip).ifPresent(parts::add);
        }
        return String.join(", ", parts);
    }

    private static String money(BigDecimal amount) { return amount.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString(); }
    private static QueryFailure exportLimit() {
        return new QueryFailure(HttpStatus.PAYLOAD_TOO_LARGE, "customer_documents_export_limit_exceeded",
                "La exportacion supera 50000 documentos; reduce los filtros");
    }
    private record Context(Scope scope, CustomerProfile customer, IssuerProfile issuer) { }
    private static final class Amounts {
        long count;
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        void add(Row row) { count++; subtotal = subtotal.add(row.subtotal()); tax = tax.add(row.taxTotal()); total = total.add(row.total()); }
        void add(Total row) { count += row.documentCount(); subtotal = subtotal.add(row.subtotal()); tax = tax.add(row.taxTotal()); total = total.add(row.total()); }
    }
    public static final class QueryFailure extends ResponseStatusException {
        private final String code;
        QueryFailure(HttpStatus status, String code, String reason) { super(status, reason); this.code = code; }
        public String getCode() { return code; }
    }
}
