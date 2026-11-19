package com.tpverp.saas.analytics;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ArticleReport(Instant asOf, ReportStatistics.Period period, ReportStatistics.Period comparisonPeriod,
                            int page, int size, long totalRows, ReportStatistics.Coverage coverage, List<Row> rows) {
    public enum Sort { AMOUNT, QUANTITY, RETURNS, CODE }
    public enum Direction { ASC, DESC }
    public record Metrics(String quantitySold, String quantityReturned, String netQuantity, String salesAmount,
                          String returnsAmount, String netAmount, long saleDocuments, String averageQuantityPerSale,
                          String amountSharePercent) {}
    public record Row(long rank, UUID companyId, String companyName, UUID storeId, String storeName, UUID productId,
                      String productCode, String productName, String currency, Metrics current, Metrics comparison,
                      String deltaAmount, String deltaPercent, String deltaQuantity) {}
}
