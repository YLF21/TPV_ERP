package com.tpverp.saas.analytics;

import com.tpverp.saas.license.LicenseSaasStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminSummary(Instant asOf, long companies, Stores stores, Licenses licenses, long installations,
                          Events events, Documents documents, List<RecentLicense> recentLicenses,
                          ReportStatistics.Period period, long newCompanies, long newStores, long newLicenses,
                          ReportStatistics.PeriodMetrics periodActivity, Catalogue catalogue, List<Daily> daily) {
    public record Catalogue(long stores, long coveredStores, Long products, Long activeProducts,
                            Instant oldestCapturedAt, Instant latestCapturedAt) {}
    public record Daily(String date, long syncEvents, List<ReportStatistics.CurrencyMetrics> currencies) {}
    public record Stores(long total, long active) {}
    public record Licenses(long total, long valid, long expired, long blocked) {}
    public record Count(String key, long count) {}
    public record Events(long total, List<Count> byType, List<Count> byStatus,
                         Instant firstReceivedAt, Instant lastReceivedAt) {}
    public record Documents(long total, List<Count> byType, List<Count> byStatus, List<Amount> amounts,
                            long storesWithDocuments, long incompleteDocuments, long unknownRelationships,
                            long observedProductReferences) {}
    public record Amount(String currency, long documents, String amount) {}
    public record RecentLicense(UUID id, String reference, String companyName, long storeCount, String storeName,
                                LicenseSaasStatus status, Instant createdAt, Instant validUntil, String province) {}
}
