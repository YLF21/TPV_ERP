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
                activity.storeTimezone(), activity.currency(), metrics(daily), metrics(previousDaily),
                daily, previousDaily, activity.topProducts());
    }

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
                sales.topProducts(companyId, store.getId(), warehouseId, range.from(), range.to()));
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

    private static Metrics metrics(List<Day> days) {
        var netSales = days.stream().map(Day::netSales).reduce(Money.euros(BigDecimal.ZERO), BigDecimal::add);
        var operationCount = days.stream().mapToLong(Day::operationCount).sum();
        // Each valid logical document is an operation, including zero totals and rectifications.
        var averageAmount = operationCount == 0 ? Money.euros(BigDecimal.ZERO)
                : netSales.divide(BigDecimal.valueOf(operationCount), Money.SCALE, Money.ROUNDING);
        return new Metrics(netSales, operationCount, averageAmount);
    }

    public record Overview(
            LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo,
            String storeTimezone, String currency, Metrics current, Metrics previous,
            List<Day> daily, List<Day> previousDaily,
            List<GestionSalesOverviewRepository.TopProduct> topProducts) {}

    public record Metrics(BigDecimal netSales, long operationCount, BigDecimal averageAmount) {}

    public record Day(LocalDate date, BigDecimal netSales, long operationCount) {}

    record Period(LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo) {}

    record Activity(
            Period period, String storeTimezone, String currency,
            List<GestionSalesOverviewRepository.DayAggregate> days,
            List<GestionSalesOverviewRepository.TopProduct> topProducts) {}
}
