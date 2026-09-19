package com.tpverp.backend.inventory;

import static com.tpverp.backend.inventory.SaasProductSalesHistoryApi.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Both entry points share this remote read. No local sales fallback or frontend-provided company. */
@Service
public class SaasProductSalesHistoryService {
    static final Set<String> SORTS = Set.of("occurredAt", "document", "status", "customer", "quantity",
            "unitPrice", "discount", "total", "user", "store", "warehouse", "currency");
    static final Set<String> STATUSES = Set.of("CONFIRMADO", "ANULADO", "PENDIENTE", "PARCIAL", "PAGADO");
    private final CurrentOrganization organization;
    private final ProductRepository products;
    private final SaasProductSalesHistoryClient client;
    private final SaasProductSalesHistoryExports exports;
    public SaasProductSalesHistoryService(CurrentOrganization organization, ProductRepository products,
            SaasProductSalesHistoryClient client, SaasProductSalesHistoryExports exports) {
        this.organization = organization; this.products = products; this.client = client; this.exports = exports;
    }
    public JsonNode page(UUID productId, Filters filters, int size, String cursor) {
        if (size < 1 || size > 200 || cursor != null && cursor.length() > 2048) invalid();
        var context = context(productId);
        var query = query(filters); query.put("size", size); query.put("cursor", cursor);
        var result = client.query("page", context.companyId(), context.storeId(), context.product().getCode(), query);
        validateResponse(result, size, context.product().getCode(), cursor);
        return result;
    }
    public byte[] excel(UUID productId, ExportRequest request) {
        var context = context(productId);
        exports.validate(request);
        return exports.excel(context.product(), request, export(context, request));
    }
    public PdfResponse pdf(UUID productId, ExportRequest request) {
        var context = context(productId);
        exports.validate(request);
        return exports.pdf(context.product(), request, export(context, request));
    }
    private JsonNode export(Context context, ExportRequest request) {
        var query = query(request.filters()); query.put("view", request.view() == null ? "detail" : request.view());
        var result = client.query("export", context.companyId(), context.storeId(), context.product().getCode(), query);
        validateResponse(result, 50_000, context.product().getCode(), null);
        if (result.path("hasMore").asBoolean()) throw SaasProductSalesHistoryException.limit();
        return result;
    }
    private Context context(UUID productId) {
        var store = organization.currentStore();
        var company = organization.currentCompany();
        if (store.getEmpresa() == null || !company.getId().equals(store.getEmpresa().getId())) {
            throw new org.springframework.security.access.AccessDeniedException("Invalid organization");
        }
        var product = products.findWithIdentifiersByStoreIdAndId(store.getId(), productId)
                .orElseThrow(() -> new NoSuchElementException("Producto no encontrado"));
        if (product.getCode() == null || product.getCode().isBlank()) throw new IllegalArgumentException("product_code_required");
        return new Context(company.getId(), store.getId(), product);
    }
    static LinkedHashMap<String, Object> query(Filters filters) {
        if (filters == null) filters = new Filters(null, null, null, List.of(), null, null);
        var from = filters.from(); var to = filters.to();
        if (from != null && to != null && from.isAfter(to)) { var previous = from; from = to; to = previous; }
        var status = filters.status() == null || filters.status().isBlank() ? null : filters.status();
        if (status != null && !STATUSES.contains(status)) invalid();
        var stores = filters.storeIds() == null ? List.<UUID>of() : filters.storeIds();
        if (stores.size() > 2000 || stores.stream().anyMatch(java.util.Objects::isNull) || new HashSet<>(stores).size() != stores.size()) invalid();
        var sort = filters.sortBy() == null || filters.sortBy().isBlank() ? "occurredAt" : filters.sortBy();
        var direction = filters.sortDirection() == null || filters.sortDirection().isBlank() ? "desc" : filters.sortDirection();
        if (!SORTS.contains(sort) || !Set.of("asc", "desc").contains(direction)) invalid();
        var query = new LinkedHashMap<String, Object>();
        query.put("from", from); query.put("to", to); query.put("status", status); query.put("storeIds", stores);
        query.put("sortBy", sort); query.put("sortDirection", direction);
        return query;
    }
    static void validateResponse(JsonNode response, int maximum, String code, String previousCursor) {
        try {
            if (!response.isObject() || !code.equals(text(response, "productCode", 255, false))
                    || !"RECEIVED_IN_SAAS".equals(text(response, "coverage", 40, false))) malformed();
            uuid(response, "companyId");
            var storeIds = new HashSet<UUID>();
            for (var store : array(response, "stores", 2000)) {
                if (!storeIds.add(uuid(store, "id"))) malformed();
                text(store, "code", 255, true); text(store, "name", 500, true);
            }
            var seen = new HashSet<String>();
            for (var row : array(response, "items", maximum)) {
                var store = uuid(row, "storeId"); var document = uuid(row, "documentId"); uuid(row, "installationId");
                long position = integer(row, "linePosition");
                if (!storeIds.contains(store) || position < 1 || !seen.add(store + ":" + document + ":" + position)) malformed();
                if (!code.equals(text(row, "productCode", 255, false)) || !STATUSES.contains(text(row, "status", 20, false))
                        || !Set.of("TICKET", "FACTURA_VENTA", "ALBARAN_VENTA", "RECTIFICATIVA_VENTA").contains(text(row, "documentType", 30, false))) malformed();
                LocalDate.parse(text(row, "businessDate", 20, false));
                var occurred = text(row, "occurredAt", 50, true); if (occurred != null) Instant.parse(occurred);
                text(row, "documentNumber", 255, false); text(row, "productName", 2000, true);
                for (String label : List.of("storeName", "storeCode", "customerName", "userName", "warehouseName")) text(row, label, 2000, true);
                for (String amount : List.of("quantity", "unitPrice", "discountPercent", "lineTotal")) decimal(row, amount);
                currency(row); bool(row, "countsAsSale");
            }
            var currencies = new HashSet<String>();
            for (var total : array(response, "totals", 100)) {
                if (!currencies.add(currency(total))) malformed(); validateAmounts(total);
            }
            var groups = new HashSet<String>();
            for (var total : array(response, "comparison", 20000)) {
                var store = uuid(total, "storeId");
                if (!storeIds.contains(store) || !groups.add(store + ":" + currency(total))) malformed();
                validateAmounts(total);
                text(total, "storeName", 500, true); text(total, "storeCode", 255, true);
            }
            boolean more = bool(response, "hasMore");
            var next = text(response, "nextCursor", 2048, true);
            if (more && (response.path("items").isEmpty() || next == null || next.isBlank() || next.equals(previousCursor))
                    || !more && next != null) malformed();
            if (integer(response, "incompleteDocuments") < 0) malformed();
            var received = text(response, "receivedAt", 50, true); if (received != null) Instant.parse(received);
        } catch (SaasProductSalesHistoryException exception) { throw exception; }
        catch (RuntimeException exception) { throw SaasProductSalesHistoryException.invalidResponse(); }
    }
    private static void validateAmounts(JsonNode value) {
        var sold = decimal(value, "quantitySold"); var returned = decimal(value, "quantityReturned");
        if (sold.signum() < 0 || returned.signum() < 0 || sold.subtract(returned).compareTo(decimal(value, "netQuantity")) != 0) malformed();
        decimal(value, "netAmount");
    }
    private static JsonNode array(JsonNode value, String field, int maximum) {
        var array = value.path(field); if (!array.isArray() || array.size() > maximum) malformed(); return array;
    }
    private static String text(JsonNode value, String field, int maximum, boolean nullable) {
        var node = value.path(field);
        if (nullable && (node.isNull() || node.isMissingNode())) return null;
        if (!node.isTextual() || node.textValue().length() > maximum) malformed(); return node.textValue();
    }
    static BigDecimal decimal(JsonNode value, String field) {
        String amount = text(value, field, 128, false);
        if (!amount.matches("-?\\d+(\\.\\d+)?")) malformed(); return new BigDecimal(amount);
    }
    private static UUID uuid(JsonNode value, String field) { return UUID.fromString(text(value, field, 36, false)); }
    private static String currency(JsonNode value) {
        var currency = text(value, "currency", 3, false); if (!currency.matches("[A-Z]{3}")) malformed(); return currency;
    }
    private static boolean bool(JsonNode value, String field) {
        if (!value.path(field).isBoolean()) malformed(); return value.path(field).booleanValue();
    }
    private static long integer(JsonNode value, String field) {
        if (!value.path(field).isIntegralNumber() || !value.path(field).canConvertToLong()) malformed(); return value.path(field).longValue();
    }
    private static void malformed() { throw SaasProductSalesHistoryException.invalidResponse(); }
    private static void invalid() { throw new IllegalArgumentException("product_history_invalid_query"); }
    private record Context(UUID companyId, UUID storeId, Product product) { }
}
