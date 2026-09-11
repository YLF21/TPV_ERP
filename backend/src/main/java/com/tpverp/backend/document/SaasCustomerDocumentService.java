package com.tpverp.backend.document;

import static com.tpverp.backend.document.SaasCustomerDocumentApi.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.tpverp.backend.document.template.CustomerModel347JasperRenderer;
import com.tpverp.backend.excel.CustomerDocumentExcelExportService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.CustomerRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/** Local permissions and binding, remote read only. No local document repository or fallback. */
@Service
public class SaasCustomerDocumentService {
    private final CurrentOrganization organization;
    private final CustomerRepository customers;
    private final SaasCustomerDocumentClient client;
    private final CustomerDocumentExcelExportService excel;
    private final CustomerModel347JasperRenderer pdf;
    public SaasCustomerDocumentService(CurrentOrganization organization, CustomerRepository customers,
            SaasCustomerDocumentClient client, CustomerDocumentExcelExportService excel, CustomerModel347JasperRenderer pdf) {
        this.organization = organization; this.customers = customers; this.client = client; this.excel = excel; this.pdf = pdf;
    }

    public Page page(UUID customerId, String reportKey, Filters filters, String sortBy, String sortDirection,
            int size, String cursor, Authentication authentication) {
        var types = permissions(reportKey, authentication);
        if (size < 1 || size > 200 || cursor != null && cursor.length() > 2048) invalid();
        var context = context(customerId);
        var query = query(reportKey, filters, sortBy, sortDirection);
        query.put("size", size); query.put("cursor", cursor);
        JsonNode response = fetch("page", context, query);
        try {
            var rows = rows(response, size, context.centralCustomerId(), types);
            boolean more = requiredBoolean(response, "hasMore");
            String next = text(response, "nextCursor", 2048, true);
            if (more && (rows.isEmpty() || next == null || next.isBlank() || next.equals(cursor))
                    || !more && next != null) malformed();
            return new Page(customerId, uuid(response, "companyId"), customer(response), rows, next, more, COVERAGE);
        } catch (SaasCustomerDocumentException exception) { throw exception; }
        catch (RuntimeException exception) { throw SaasCustomerDocumentException.invalidResponse(); }
    }

    public byte[] export(ExportRequest request, Authentication authentication) {
        if (request == null) invalid();
        var types = permissions(request.reportKey(), authentication);
        var filter = request.filters() == null ? new Filters(null, null, null, null) : request.filters();
        var query = query(request.reportKey(), filter, request.sortBy(), request.sortDirection());
        var keys = request.documentKeys();
        if (filter.active() ? keys != null : keys == null) invalid();
        if (keys != null) {
            if (keys.size() > 50_000) throw SaasCustomerDocumentException.limit();
            if (keys.stream().anyMatch(key -> key == null || key.storeId() == null || key.documentId() == null)
                    || new HashSet<>(keys).size() != keys.size()) invalid();
        }
        var presentation = request.presentation();
        CustomerDocumentExcelExportService.validateReceivedPresentation(presentation, types);
        var context = context(request.customerId());
        query.put("documentKeys", keys);
        JsonNode response = fetch("export", context, query);
        List<Row> rows;
        CustomerProfile customer;
        try {
            rows = rows(response, 50_000, context.centralCustomerId(), types);
            customer = customer(response);
            if (keys != null && !rows.stream().map(row -> new DocumentKey(row.storeId(), row.documentId())).toList().equals(keys)) malformed();
            validateTotals(response, rows);
        } catch (SaasCustomerDocumentException exception) { throw exception; }
        catch (RuntimeException exception) { throw SaasCustomerDocumentException.invalidResponse(); }
        var exportRows = rows.stream().map(row -> new CustomerDocumentExcelExportService.ExportRow(
                row.documentId(), row.number(), row.date(), row.type(), row.status(), new BigDecimal(row.subtotal()),
                new BigDecimal(row.taxTotal()), new BigDecimal(row.total()), row.terminalName(), row.userName(),
                row.storeCode(), row.currency())).toList();
        return excel.renderReceived(presentation, customer.code(), customer.taxId(), customer.name(), exportRows);
    }

    public byte[] annual(UUID customerId, int year, String locale, Authentication authentication) {
        permissions("invoices", authentication);
        if (year < 1 || year > 9998 || locale == null || !Set.of("es", "en", "zh").contains(locale)) invalid();
        var context = context(customerId);
        JsonNode response = fetch("annual", context, Map.of("year", year));
        CustomerModel347Report report;
        try {
            if (integer(response, "year") != year) malformed();
            var customer = customer(response);
            JsonNode issuer = response.path("issuer");
            if (!uuid(issuer, "id").equals(uuid(response, "companyId"))) malformed();
            var quarters = new TreeMap<Integer, CustomerModel347Report.Quarter>();
            BigDecimal annual = new BigDecimal("0.00");
            long annualCount = 0;
            for (JsonNode item : array(response, "quarters", 400)) {
                requireEur(currency(item));
                int number = Math.toIntExact(integer(item, "number"));
                long count = integer(item, "documentCount");
                if (number < 1 || number > 4 || count < 0) malformed();
                BigDecimal amount = money(item, "total", 64);
                if (quarters.put(number, new CustomerModel347Report.Quarter(number, amount, count)) != null) malformed();
                annual = annual.add(amount); annualCount = Math.addExact(annualCount, count);
            }
            var totals = array(response, "totals", 100);
            for (JsonNode total : totals) requireEur(currency(total));
            if (quarters.isEmpty()) {
                if (!totals.isEmpty()) malformed();
                for (int number = 1; number <= 4; number++) quarters.put(number, new CustomerModel347Report.Quarter(number, BigDecimal.ZERO.setScale(2), 0));
            } else if (quarters.size() != 4 || totals.size() != 1
                    || annual.compareTo(money(totals.getFirst(), "total", 64)) != 0
                    || annualCount != integer(totals.getFirst(), "documentCount")) malformed();
            report = new CustomerModel347Report(year,
                    new CustomerModel347Report.Party("", text(issuer, "taxId", 64, true), text(issuer, "name", 255, false), text(issuer, "address", 2000, true)),
                    new CustomerModel347Report.Party(customer.code(), customer.taxId(), customer.name(), customer.address()),
                    new ArrayList<>(quarters.values()), annual);
        } catch (SaasCustomerDocumentException exception) { throw exception; }
        catch (RuntimeException exception) { throw SaasCustomerDocumentException.invalidResponse(); }
        return pdf.render(report, locale);
    }

    private Context context(UUID customerId) {
        if (customerId == null) invalid();
        var company = organization.currentCompany();
        var store = organization.currentStore();
        if (company == null || store == null || store.getEmpresa() == null || !company.getId().equals(store.getEmpresa().getId())) {
            throw new AccessDeniedException("Organización no válida");
        }
        var customer = customers.findByIdAndCompanyId(customerId, company.getId())
                .orElseThrow(() -> new NoSuchElementException("Cliente no encontrado"));
        if (customer.getSaasCustomerId() == null) throw SaasCustomerDocumentException.binding();
        return new Context(company.getId(), store.getId(), customerId, customer.getSaasCustomerId());
    }
    private JsonNode fetch(String operation, Context context, Map<String, Object> query) {
        return client.query(operation, context.companyId(), context.storeId(), context.customerId(), context.centralCustomerId(), query);
    }
    private static Set<CommercialDocumentType> permissions(String reportKey, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) throw new AccessDeniedException("Autenticación requerida");
        if (reportKey == null) invalid();
        return CustomerDocumentExcelExportService.allowedTypes(reportKey, authentication);
    }
    private static LinkedHashMap<String, Object> query(String reportKey, Filters filters, String sortBy, String sortDirection) {
        var result = new LinkedHashMap<String, Object>();
        result.put("reportKey", reportKey); result.put("filters", filters);
        result.put("sortBy", sort(sortBy)); result.put("sortDirection", direction(sortDirection));
        return result;
    }
    private static CustomerProfile customer(JsonNode response) {
        JsonNode value = response.path("customer");
        return new CustomerProfile(uuid(value, "id"), text(value, "code", 40, false), text(value, "name", 255, false),
                text(value, "taxId", 64, true), text(value, "address", 2000, true));
    }
    private static List<Row> rows(JsonNode response, int maximum, UUID customerId, Set<CommercialDocumentType> types) {
        var result = new ArrayList<Row>();
        var seen = new HashSet<String>();
        for (JsonNode value : array(response, "items", maximum)) {
            UUID store = uuid(value, "storeId"), document = uuid(value, "documentId");
            String id = text(value, "id", 73, false);
            var type = CommercialDocumentType.valueOf(text(value, "type", 32, false));
            var status = DocumentStatus.valueOf(text(value, "status", 16, false));
            if (!id.equals(store + "/" + document) || !seen.add(id) || !customerId.equals(uuid(value, "customerId"))
                    || !types.contains(type) || status == DocumentStatus.BORRADOR || integer(value, "sourceRevision") < 0) malformed();
            String dateText = text(value, "date", 10, false);
            if (!dateText.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) malformed();
            LocalDate date = LocalDate.parse(dateText);
            if (date.getYear() < 1) malformed();
            result.add(new Row(id, store, text(value, "storeCode", 64, false), document, uuid(value, "installationId"),
                    integer(value, "sourceRevision"), customerId, type, status, text(value, "number", 32, false), date,
                    currency(value), money(value, "subtotal", 19).toPlainString(), money(value, "taxTotal", 19).toPlainString(),
                    money(value, "total", 19).toPlainString(), text(value, "terminalName", 255, true), text(value, "userName", 255, true)));
        }
        return List.copyOf(result);
    }
    private static void validateTotals(JsonNode response, List<Row> rows) {
        var sums = new TreeMap<String, BigDecimal[]>();
        var counts = new TreeMap<String, Long>();
        for (Row row : rows) {
            var sum = sums.computeIfAbsent(row.currency(), key -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            sum[0] = sum[0].add(new BigDecimal(row.subtotal())); sum[1] = sum[1].add(new BigDecimal(row.taxTotal())); sum[2] = sum[2].add(new BigDecimal(row.total()));
            counts.merge(row.currency(), 1L, Long::sum);
        }
        var seen = new HashSet<String>();
        for (JsonNode total : array(response, "totals", 100)) {
            String currency = currency(total);
            if (!seen.add(currency) || !sums.containsKey(currency) || counts.get(currency) != integer(total, "documentCount")) malformed();
            var sum = sums.get(currency);
            if (sum[0].compareTo(money(total, "subtotal", 64)) != 0 || sum[1].compareTo(money(total, "taxTotal", 64)) != 0
                    || sum[2].compareTo(money(total, "total", 64)) != 0) malformed();
        }
        if (!seen.equals(sums.keySet())) malformed();
    }
    private static List<JsonNode> array(JsonNode parent, String key, int maximum) {
        JsonNode value = parent.path(key);
        if (!value.isArray() || value.size() > maximum) malformed();
        var result = new ArrayList<JsonNode>(value.size()); value.forEach(result::add); return result;
    }
    private static String text(JsonNode parent, String key, int maximum, boolean nullable) {
        JsonNode value = parent.get(key);
        if (nullable && (value == null || value.isNull())) return null;
        if (value == null || !value.isTextual() || value.textValue().length() > maximum) malformed();
        return value.textValue();
    }
    private static UUID uuid(JsonNode parent, String key) {
        String value = text(parent, key, 36, false);
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) malformed();
        return UUID.fromString(value);
    }
    private static long integer(JsonNode parent, String key) {
        JsonNode value = parent.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) malformed();
        return value.longValue();
    }
    private static boolean requiredBoolean(JsonNode parent, String key) {
        if (!parent.path(key).isBoolean()) malformed(); return parent.path(key).booleanValue();
    }
    private static String currency(JsonNode parent) {
        String value = text(parent, "currency", 3, false);
        if (!value.matches("[A-Z]{3}")) malformed(); return value;
    }
    private static BigDecimal money(JsonNode parent, String key, int precision) {
        String value = text(parent, key, 64, false);
        if (!value.matches("-?[0-9]+(?:\\.[0-9]+)?")) malformed();
        BigDecimal decimal = new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
        if (decimal.precision() > precision) malformed(); return decimal;
    }
    private static void requireEur(String currency) {
        if (!"EUR".equals(currency)) throw new SaasCustomerDocumentException(HttpStatus.UNPROCESSABLE_CONTENT,
                "SAAS_CUSTOMER_DOCUMENTS_CURRENCY_UNSUPPORTED");
    }
    private static void malformed() { throw SaasCustomerDocumentException.invalidResponse(); }
    private record Context(UUID companyId, UUID storeId, UUID customerId, UUID centralCustomerId) { }
}
