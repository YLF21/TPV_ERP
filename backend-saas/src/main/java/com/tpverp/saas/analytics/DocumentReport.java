package com.tpverp.saas.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record DocumentReport(Instant asOf, ReportStatistics.Period period, int page, int size, long totalRows,
                             ReportStatistics.Coverage coverage, List<Row> rows) {
    public enum Sort { DATE, NUMBER, AMOUNT, CURRENCY }
    public enum Direction { ASC, DESC }
    public record Row(UUID documentId, String type, String number, String status, LocalDate businessDate,
                      UUID companyId, String companyName, UUID storeId, String storeName, String currency,
                      String total, Boolean eligible, boolean linesComplete, boolean relationshipsComplete) {}
}
