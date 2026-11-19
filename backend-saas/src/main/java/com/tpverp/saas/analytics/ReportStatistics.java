package com.tpverp.saas.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Document dates use business_date. Receipt dates use received_at in UTC, including stock movement events. */
public record ReportStatistics(Instant asOf, Period period, Period comparisonPeriod, GroupBy groupBy,
        int page, int size, long totalGroups, Coverage coverage, PeriodPair totals, List<Row> rows,
        List<EventCount> syncByType, List<EventCount> syncByStatus) {
    public enum GroupBy { DAY, WEEK, MONTH, COMPANY, STORE, PROVINCE }
    public enum Comparison { NONE, PREVIOUS_PERIOD, PREVIOUS_YEAR, CUSTOM }

    public record Period(LocalDate from, LocalDate to, int days) {
        public static Period inclusive(LocalDate from, LocalDate to) {
            return inclusive(from, to, 366);
        }

        private static Period inclusive(LocalDate from, LocalDate to, int maxDays) {
            if (from == null || to == null || from.getYear() < 1 || to.getYear() > 9999) throw invalid("period");
            long days = ChronoUnit.DAYS.between(from, to) + 1;
            if (days < 1 || days > maxDays) throw invalid("period");
            return new Period(from, to, (int) days);
        }

        public Period comparison(Comparison mode, LocalDate customFrom, LocalDate customTo) {
            if (mode != Comparison.CUSTOM && (customFrom != null || customTo != null)) throw invalid("comparison");
            return switch (mode) {
                case NONE -> null;
                case PREVIOUS_PERIOD -> inclusive(from.minusDays(days), from.minusDays(1));
                case PREVIOUS_YEAR -> inclusive(from.minusYears(1), to.minusYears(1), 367);
                case CUSTOM -> inclusive(customFrom, customTo);
            };
        }

        LocalDate bucketStart(GroupBy group, int ordinal) {
            LocalDate start = switch (group) {
                case DAY -> from.plusDays(ordinal);
                case WEEK -> from.minusDays(from.getDayOfWeek().getValue() - 1L).plusWeeks(ordinal);
                case MONTH -> from.withDayOfMonth(1).plusMonths(ordinal);
                default -> throw invalid("groupBy");
            };
            if (start.isAfter(to)) return null;
            return start.isBefore(from) ? from : start;
        }

        int bucketDays(GroupBy group, int ordinal) {
            LocalDate start = bucketStart(group, ordinal);
            if (start == null) return 0;
            LocalDate next = bucketStart(group, ordinal + 1);
            return (int) ChronoUnit.DAYS.between(start, next == null ? to.plusDays(1) : next);
        }
    }

    static ResponseStatusException invalid(String field) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filtro de informe no válido (" + field + ")");
    }

    public record Coverage(long unknownRelationships, long currentIncompleteDocuments, Long comparisonIncompleteDocuments) {}
    public record CurrencyMetrics(String currency, Long documents, String amount, String averageAmount,
                                  String amountPerDay, Long contributingStores, String amountPerStore) {}
    public record PeriodMetrics(long documents, Long eligibleDocuments, long cancelledDocuments, long syncEvents,
            long syncErrors, long stockMovementEvents, List<CurrencyMetrics> currencies, long stores, long licenses,
            Long saleDocuments, Long returnDocuments) {}
    public record Delta(String currency, String amount, String percent) {}
    public record PeriodPair(PeriodMetrics current, PeriodMetrics comparison, List<Delta> deltas) {}
    public record Row(String key, String label, String comparisonLabel, PeriodMetrics current,
                      PeriodMetrics comparison, List<Delta> deltas) {}
    public record EventCount(String key, long current, Long comparison) {}
    public record Options(List<CompanyOption> companies, List<StoreOption> stores) {}
    public record CompanyOption(UUID id, String name) {}
    public record StoreOption(UUID id, UUID companyId, String name) {}
}
