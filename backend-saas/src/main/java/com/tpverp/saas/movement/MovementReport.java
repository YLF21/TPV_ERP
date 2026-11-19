package com.tpverp.saas.movement;

import com.tpverp.saas.analytics.ReportStatistics.Period;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MovementReport {
    private MovementReport() {}

    public enum GroupBy {
        TYPE,
        WAREHOUSE,
        STORE,
        PROVINCE,
        DAY
    }

    public enum Sort {
        OCCURRED_AT,
        RECEIVED_AT,
        TYPE,
        PRODUCT,
        WAREHOUSE
    }

    public enum Direction {
        ASC,
        DESC
    }

    public enum Type {
        AJUSTE,
        TRANSFERENCIA_SALIDA,
        TRANSFERENCIA_ENTRADA,
        SALIDA_ALMACEN,
        ENTRADA_ALMACEN,
        ALBARAN_ENTRADA,
        FACTURA_ENTRADA,
        ALBARAN_VENTA,
        TICKET,
        FACTURA_VENTA,
        ANULACION
    }

    public record Filter(
            UUID companyId,
            UUID storeId,
            String province,
            UUID warehouseId,
            UUID productId,
            Type type) {}

    public record Coverage(
            long malformedReceipts, long conflictingMovements, long duplicateReceipts) {}

    public record Bucket(
            String key,
            String label,
            String comparisonLabel,
            Long currentCount,
            Long comparisonCount) {}

    public record Summary(
            Instant asOf,
            Period period,
            Period comparisonPeriod,
            int page,
            int size,
            long totalGroups,
            GroupBy groupBy,
            long currentCount,
            Long comparisonCount,
            Coverage coverage,
            List<Bucket> rows) {}

    public record Movement(
            UUID movementId,
            UUID companyId,
            String companyName,
            UUID storeId,
            String storeName,
            String province,
            UUID warehouseId,
            String warehouseName,
            UUID productId,
            String productName,
            String type,
            String quantity,
            Instant occurredAt,
            Instant receivedAt) {}

    public record Ledger(int page, int size, long total, List<Movement> rows) {}
}
