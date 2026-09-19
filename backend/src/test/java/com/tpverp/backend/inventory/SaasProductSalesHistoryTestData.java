package com.tpverp.backend.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;

final class SaasProductSalesHistoryTestData {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final UUID COMPANY = UUID.randomUUID(), STORE = UUID.randomUUID(), INSTALLATION = UUID.randomUUID();
    static ObjectNode response() {
        var root = MAPPER.createObjectNode(); root.put("companyId", COMPANY.toString()); root.put("productCode", "00042");
        root.put("coverage", "RECEIVED_IN_SAAS"); root.put("hasMore", false); root.putNull("nextCursor"); root.put("incompleteDocuments", 0);
        root.put("receivedAt", "2026-09-19T12:00:00Z");
        var store = root.putArray("stores").addObject(); store.put("id", STORE.toString()); store.put("code", "001"); store.put("name", "Tienda de ejemplo");
        root.putArray("items").add(row(1));
        var total = root.putArray("totals").addObject(); amounts(total);
        var comparison = root.putArray("comparison").addObject(); amounts(comparison);
        comparison.put("storeId", STORE.toString()); comparison.put("storeCode", "001"); comparison.put("storeName", "Tienda de ejemplo");
        return root;
    }
    static ObjectNode row(int number) {
        var row = MAPPER.createObjectNode(); row.put("documentId", UUID.randomUUID().toString()); row.put("documentType", "TICKET");
        row.put("documentNumber", "T-001-" + number); row.put("status", "CONFIRMADO"); row.put("businessDate", "2026-09-19");
        row.put("occurredAt", "2026-09-19T10:15:00Z"); row.put("storeId", STORE.toString()); row.put("storeCode", "001"); row.put("storeName", "Tienda de ejemplo");
        row.put("installationId", INSTALLATION.toString()); row.put("linePosition", number); row.put("productCode", "00042"); row.put("productName", "Artículo ficticio");
        row.put("quantity", "2"); row.put("unitPrice", "1.875"); row.put("discountPercent", "0"); row.put("lineTotal", "3.75"); row.put("currency", "EUR");
        row.put("customerName", "Cliente ficticio con una denominación extensa para comprobar el ajuste de la tabla");
        row.put("userName", "Operador de ejemplo"); row.putNull("warehouseName"); row.put("countsAsSale", true); return row;
    }
    private static void amounts(ObjectNode value) {
        value.put("currency", "EUR"); value.put("quantitySold", "2"); value.put("quantityReturned", "0"); value.put("netQuantity", "2"); value.put("netAmount", "3.75");
    }
}
