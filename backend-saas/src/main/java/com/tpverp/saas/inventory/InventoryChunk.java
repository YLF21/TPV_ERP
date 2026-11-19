package com.tpverp.saas.inventory;

import com.tpverp.saas.sync.SaasSyncEvent;
import com.tpverp.saas.sync.SyncEventRequest;
import com.tpverp.saas.sync.SyncOperation;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

record InventoryChunk(
        UUID snapshotId,
        Instant capturedAt,
        int index,
        int count,
        int products,
        int warehouses,
        int balances,
        int productRows,
        int warehouseRows,
        int balanceRows) {
    private static final Set<String> HEADER =
            Set.of(
                    "schemaVersion",
                    "sourceInstallationId",
                    "snapshotId",
                    "capturedAt",
                    "chunkIndex",
                    "chunkCount",
                    "productCount",
                    "warehouseCount",
                    "balanceCount",
                    "products",
                    "warehouses",
                    "balances");
    private static final Set<String> PRODUCT =
            Set.of(
                    "productId",
                    "name",
                    "code",
                    "barcode",
                    "secondaryBarcode",
                    "active",
                    "productType",
                    "familyId",
                    "familyName",
                    "subfamilyId",
                    "subfamilyName");
    private static final Set<String> WAREHOUSE =
            Set.of("warehouseId", "name", "active", "defaultWarehouse");
    private static final Set<String> BALANCE = Set.of("productId", "warehouseId", "quantity");
    private static final Set<String> TYPES = Set.of("UNIT", "WEIGHT", "SERVICE");
    private static final Pattern QUANTITY =
            Pattern.compile("-?(?:0|[1-9][0-9]{0,15})(?:\\.[0-9]{1,3})?");

    static InventoryChunk validate(SaasSyncEvent event, SyncEventRequest request, Instant now) {
        var data = request.payload();
        if (request.operation() != SyncOperation.ACTUALIZAR
                || !HEADER.equals(data.keySet())
                || integer(data, "schemaVersion") != 1) throw invalid("cabecera");
        UUID id = uuid(data, "snapshotId");
        if (!id.equals(request.entityId())
                || !uuid(data, "sourceInstallationId")
                        .equals(event.getInstallation().getInstallationId())
                || !event.getInstallation().isActive()
                || !event.getStore().getId().equals(request.storeId())) {
            throw invalid("origen de la captura");
        }
        String time = text(data, "capturedAt", 32, true);
        Instant captured;
        try {
            captured = Instant.parse(time);
        } catch (DateTimeParseException e) {
            throw invalid("capturedAt");
        }
        if (!time.endsWith("Z")
                || captured.getNano() % 1000 != 0
                || captured.isAfter(now.plusSeconds(300))
                || captured.isBefore(Instant.parse("0001-01-01T00:00:00Z")))
            throw invalid("capturedAt");
        int chunks = integer(data, "chunkCount"), index = integer(data, "chunkIndex");
        int products = integer(data, "productCount"),
                warehouses = integer(data, "warehouseCount"),
                balances = integer(data, "balanceCount");
        var p = list(data, "products");
        var w = list(data, "warehouses");
        var b = list(data, "balances");
        long rows = (long) p.size() + w.size() + b.size(),
                total = (long) products + warehouses + balances;
        if (index >= chunks
                || chunks > 10_000
                || total > 5_000_000
                || chunks > Math.max(1L, total)
                || total > 500L * chunks
                || rows > 500
                || (rows == 0 && (chunks != 1 || total != 0))
                || p.size() > products
                || w.size() > warehouses
                || b.size() > balances) throw invalid("recuentos");
        for (Object entry : p) {
            var row = object(entry, PRODUCT);
            uuid(row, "productId");
            text(row, "name", 255, true);
            bool(row, "active");
            for (var field : List.of("code", "barcode", "secondaryBarcode"))
                text(row, field, 128, false);
            if (!TYPES.contains(text(row, "productType", 7, true))) throw invalid("productType");
            dimension(row, "familyId", "familyName");
            dimension(row, "subfamilyId", "subfamilyName");
        }
        for (Object entry : w) {
            var row = object(entry, WAREHOUSE);
            uuid(row, "warehouseId");
            text(row, "name", 128, true);
            bool(row, "active");
            bool(row, "defaultWarehouse");
        }
        for (Object entry : b) {
            var row = object(entry, BALANCE);
            uuid(row, "productId");
            uuid(row, "warehouseId");
            if (!QUANTITY.matcher(text(row, "quantity", 21, true)).matches())
                throw invalid("quantity");
        }
        return new InventoryChunk(
                id,
                captured,
                index,
                chunks,
                products,
                warehouses,
                balances,
                p.size(),
                w.size(),
                b.size());
    }

    private static int integer(Map<?, ?> map, String key) {
        if (map.get(key) instanceof Integer n && n >= 0) return n;
        if (map.get(key) instanceof Long n && n >= 0 && n <= Integer.MAX_VALUE) return n.intValue();
        throw invalid(key);
    }

    private static List<?> list(Map<?, ?> map, String key) {
        if (map.get(key) instanceof List<?> list) return list;
        throw invalid(key);
    }

    private static Map<?, ?> object(Object value, Set<String> fields) {
        if (value instanceof Map<?, ?> row && fields.containsAll(row.keySet())) return row;
        throw invalid("campos de fila");
    }

    private static UUID uuid(Map<?, ?> row, String key) {
        String value = text(row, key, 36, true);
        try {
            var id = UUID.fromString(value);
            if (id.toString().equalsIgnoreCase(value)) return id;
        } catch (IllegalArgumentException ignored) {
        }
        throw invalid(key);
    }

    private static String text(Map<?, ?> row, String key, int max, boolean required) {
        var value = row.get(key);
        if (value == null && !required) return null;
        if (value instanceof String string
                && string.codePointCount(0, string.length()) <= max
                && !string.isBlank()
                && string.indexOf(0) < 0) return string;
        throw invalid(key);
    }

    private static void dimension(Map<?, ?> row, String id, String name) {
        if (row.get(id) != null) uuid(row, id);
        if ((row.get(id) == null) != (text(row, name, 128, false) == null)) throw invalid(name);
    }

    private static void bool(Map<?, ?> row, String key) {
        if (!(row.get(key) instanceof Boolean)) throw invalid(key);
    }

    private static ResponseStatusException invalid(String field) {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Captura de inventario invalida: " + field);
    }
}
