package com.tpverp.backend.ui;

import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.document.Money;
import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class GestionSalesOverviewService {

    private final GestionSalesOverviewRepository sales;
    private final WarehouseRepository warehouses;
    private final CurrentOrganization organization;

    public GestionSalesOverviewService(
            GestionSalesOverviewRepository sales,
            WarehouseRepository warehouses,
            CurrentOrganization organization) {
        this.sales = sales;
        this.warehouses = warehouses;
        this.organization = organization;
    }

    public Overview overview(LocalDate from, LocalDate to, UUID warehouseId) {
        var activity = readActivity(from, to, warehouseId);
        var range = activity.period();
        var byDate = activity.days().stream().collect(Collectors.toMap(
                GestionSalesOverviewRepository.DayAggregate::date, Function.identity()));
        var daily = series(range.from(), range.to(), byDate);
        var previousDaily = series(range.previousFrom(), range.previousTo(), byDate);
        return new Overview(
                range.from(), range.to(), range.previousFrom(), range.previousTo(),
                activity.storeTimezone(), activity.currency(),
                metrics(daily, activity.families().stream().map(GestionSalesOverviewRepository.FamilySales::currentUnits).reduce(BigDecimal.ZERO, BigDecimal::add)),
                metrics(previousDaily, activity.families().stream().map(GestionSalesOverviewRepository.FamilySales::previousUnits).reduce(BigDecimal.ZERO, BigDecimal::add)),
                daily, previousDaily, activity.topProducts(), activity.topProductsByAmount(), activity.families(),
                activity.hourly(), activity.corrections(), activity.payments());
    }

    public HourlyComparison hourlyComparison(LocalDate day, LocalDate comparisonDay, UUID warehouseId) {
        return hourlyComparison(day, comparisonDay, null, null, null, null, warehouseId);
    }

    public HourlyComparison hourlyComparison(LocalDate day, LocalDate comparisonDay,
            LocalDate from, LocalDate to, LocalDate comparisonFrom, LocalDate comparisonTo, UUID warehouseId) {
        var currentRange = hourlyRange(from, to, day, true);
        var comparisonRange = hourlyRange(comparisonFrom, comparisonTo, comparisonDay, false);
        var store = organization.currentStore();
        if (warehouseId != null
                && warehouses.findByStoreIdAndIdIn(store.getId(), List.of(warehouseId)).isEmpty()) {
            throw new IllegalArgumentException("message.warehouse.not_found");
        }
        var companyId = store.getEmpresa().getId();
        var current = sumByHour(currentRange.from(), sales.hourly(companyId, store.getId(), warehouseId,
                currentRange.from(), currentRange.to(), store.getTimezone()));
        var previous = comparisonRange == null ? List.<GestionSalesOverviewRepository.HourSales>of()
                : currentRange.equals(comparisonRange) ? current
                : sumByHour(comparisonRange.from(), sales.hourly(companyId, store.getId(), warehouseId,
                        comparisonRange.from(), comparisonRange.to(), store.getTimezone()));
        return new HourlyComparison(currentRange.from(), comparisonRange == null ? null : comparisonRange.from(),
                currentRange.from(), currentRange.to(),
                comparisonRange == null ? null : comparisonRange.from(),
                comparisonRange == null ? null : comparisonRange.to(),
                store.getTimezone(), "EUR", current, previous);
    }

    private static HourlyRange hourlyRange(LocalDate from, LocalDate to, LocalDate legacyDay, boolean required) {
        if (legacyDay != null && (legacyDay.getYear() < 1 || legacyDay.getYear() > 9999)) {
            throw new IllegalArgumentException("message.dashboard.sales_range_invalid");
        }
        if (from == null && to == null) {
            if (legacyDay == null) {
                if (!required) return null;
                throw new IllegalArgumentException("message.dashboard.sales_range_invalid");
            }
            from = legacyDay;
            to = legacyDay;
        }
        if (from == null || to == null || from.getYear() < 1 || to.getYear() > 9999
                || ChronoUnit.DAYS.between(from, to) < 0 || ChronoUnit.DAYS.between(from, to) >= 366) {
            throw new IllegalArgumentException("message.dashboard.sales_range_invalid");
        }
        return new HourlyRange(from, to);
    }

    private static List<GestionSalesOverviewRepository.HourSales> sumByHour(LocalDate rangeFrom,
            List<GestionSalesOverviewRepository.HourSales> dailyHours) {
        var hours = new TreeMap<Integer, GestionSalesOverviewRepository.HourSales>();
        for (var row : dailyHours) {
            hours.merge(row.hour(), new GestionSalesOverviewRepository.HourSales(
                    rangeFrom, row.hour(), row.sales(), row.units(), row.operations()),
                    (left, right) -> new GestionSalesOverviewRepository.HourSales(rangeFrom, left.hour(),
                            left.sales().add(right.sales()), left.units().add(right.units()),
                            left.operations() + right.operations()));
        }
        return List.copyOf(hours.values());
    }

    private record HourlyRange(LocalDate from, LocalDate to) {}

    public record HourlyComparison(LocalDate day, LocalDate comparisonDay,
            LocalDate from, LocalDate to, LocalDate comparisonFrom, LocalDate comparisonTo,
            String storeTimezone, String currency,
            List<GestionSalesOverviewRepository.HourSales> current,
            List<GestionSalesOverviewRepository.HourSales> previous) {}

    Activity readActivity(LocalDate from, LocalDate to, UUID warehouseId) {
        var range = period(from, to);
        var store = organization.currentStore();
        if (warehouseId != null
                && warehouses.findByStoreIdAndIdIn(store.getId(), List.of(warehouseId)).isEmpty()) {
            throw new IllegalArgumentException("message.warehouse.not_found");
        }
        var companyId = store.getEmpresa().getId();
        return new Activity(
                // Persisted commercial documents are constrained to EUR, independently of store settings.
                range, store.getTimezone(), "EUR",
                sales.daily(companyId, store.getId(), warehouseId, range.previousFrom(), range.to()),
                sales.topProducts(companyId, store.getId(), warehouseId, range.from(), range.to()),
                sales.topProductsByAmount(companyId, store.getId(), warehouseId, range.from(), range.to()),
                sales.families(companyId, store.getId(), warehouseId, range.previousFrom(), range.to(), range.from()),
                sales.hourly(companyId, store.getId(), warehouseId, range.from(), range.to(), store.getTimezone()),
                sales.corrections(companyId, store.getId(), warehouseId, range.from(), range.to()),
                sales.payments(companyId, store.getId(), warehouseId, range.from(), range.to(), store.getTimezone()));
    }

    static Period period(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("message.dashboard.sales_dates_required");
        }
        var days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days < 1 || days > 366 || from.getYear() < 1 || to.getYear() > 9999) {
            throw new IllegalArgumentException("message.dashboard.sales_range_invalid");
        }
        try {
            var previousFrom = from.minusDays(days);
            if (previousFrom.getYear() < 1) {
                throw new IllegalArgumentException("message.dashboard.sales_range_invalid");
            }
            return new Period(from, to, previousFrom, from.minusDays(1));
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("message.dashboard.sales_range_invalid", exception);
        }
    }

    private static List<Day> series(
            LocalDate from, LocalDate to,
            Map<LocalDate, GestionSalesOverviewRepository.DayAggregate> byDate) {
        return from.datesUntil(to.plusDays(1)).map(date -> {
            var aggregate = byDate.get(date);
            return aggregate == null
                    ? new Day(date, Money.euros(BigDecimal.ZERO), 0)
                    : new Day(date, Money.euros(aggregate.netSales()), aggregate.documentCount());
        }).toList();
    }

    private static Metrics metrics(List<Day> days, BigDecimal netUnits) {
        var netSales = days.stream().map(Day::netSales).reduce(Money.euros(BigDecimal.ZERO), BigDecimal::add);
        var operationCount = days.stream().mapToLong(Day::operationCount).sum();
        // Each valid logical document is an operation, including zero totals and rectifications.
        var averageAmount = operationCount == 0 ? Money.euros(BigDecimal.ZERO)
                : netSales.divide(BigDecimal.valueOf(operationCount), Money.SCALE, Money.ROUNDING);
        return new Metrics(netSales, operationCount, averageAmount, netUnits.stripTrailingZeros());
    }

    public record Overview(
            LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo,
            String storeTimezone, String currency, Metrics current, Metrics previous,
            List<Day> daily, List<Day> previousDaily,
            List<GestionSalesOverviewRepository.TopProduct> topProducts,
            List<GestionSalesOverviewRepository.TopProduct> topProductsByAmount,
            List<GestionSalesOverviewRepository.FamilySales> families,
            List<GestionSalesOverviewRepository.HourSales> hourly,
            List<GestionSalesOverviewRepository.CorrectionSummary> corrections,
            List<GestionSalesOverviewRepository.PaymentSummary> payments) {}

    public record Metrics(BigDecimal netSales, long operationCount, BigDecimal averageAmount, BigDecimal netUnits) {
        public Metrics(BigDecimal netSales, long operationCount, BigDecimal averageAmount) {
            this(netSales, operationCount, averageAmount, BigDecimal.ZERO);
        }
    }

    public record Day(LocalDate date, BigDecimal netSales, long operationCount) {}

    record Period(LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo) {}

    record Activity(
            Period period, String storeTimezone, String currency,
            List<GestionSalesOverviewRepository.DayAggregate> days,
            List<GestionSalesOverviewRepository.TopProduct> topProducts,
            List<GestionSalesOverviewRepository.TopProduct> topProductsByAmount,
            List<GestionSalesOverviewRepository.FamilySales> families,
            List<GestionSalesOverviewRepository.HourSales> hourly,
            List<GestionSalesOverviewRepository.CorrectionSummary> corrections,
            List<GestionSalesOverviewRepository.PaymentSummary> payments) {}
}
