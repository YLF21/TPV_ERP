package com.tpverp.backend.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GestionSalesOverviewServiceTest {

    @Test
    void fillsOrderedDaysAndComputesBothPeriodsFromAllOperationsIncludingZeroAndNegativeTotals() {
        var fixture = fixture();
        var from = LocalDate.of(2026, 8, 1);
        var to = from.plusDays(3);
        when(fixture.sales().daily(fixture.companyId(), fixture.store().getId(), null, from.minusDays(4), to))
                .thenReturn(List.of(
                        new GestionSalesOverviewRepository.DayAggregate(to, new BigDecimal("-3.00"), 1),
                        new GestionSalesOverviewRepository.DayAggregate(from.minusDays(4), new BigDecimal("10.01"), 2),
                        new GestionSalesOverviewRepository.DayAggregate(from.plusDays(1), new BigDecimal("0.00"), 1),
                        new GestionSalesOverviewRepository.DayAggregate(from, new BigDecimal("20.00"), 2)));
        var product = new GestionSalesOverviewRepository.TopProduct(
                UUID.randomUUID(), "TOP", "Top product", new BigDecimal("2.500"));
        when(fixture.sales().topProducts(fixture.companyId(), fixture.store().getId(), null, from, to))
                .thenReturn(List.of(product));

        var result = fixture.service().overview(from, to, null);

        assertThat(result.from()).isEqualTo(from);
        assertThat(result.to()).isEqualTo(to);
        assertThat(result.previousFrom()).isEqualTo(from.minusDays(4));
        assertThat(result.previousTo()).isEqualTo(from.minusDays(1));
        assertThat(result.current()).isEqualTo(new GestionSalesOverviewService.Metrics(
                new BigDecimal("17.00"), 4, new BigDecimal("4.25")));
        assertThat(result.previous()).isEqualTo(new GestionSalesOverviewService.Metrics(
                new BigDecimal("10.01"), 2, new BigDecimal("5.01")));
        assertThat(result.daily()).containsExactly(
                new GestionSalesOverviewService.Day(from, new BigDecimal("20.00"), 2),
                new GestionSalesOverviewService.Day(from.plusDays(1), new BigDecimal("0.00"), 1),
                new GestionSalesOverviewService.Day(from.plusDays(2), new BigDecimal("0.00"), 0),
                new GestionSalesOverviewService.Day(to, new BigDecimal("-3.00"), 1));
        assertThat(result.previousDaily()).extracting(GestionSalesOverviewService.Day::date)
                .containsExactly(from.minusDays(4), from.minusDays(3), from.minusDays(2), from.minusDays(1));
        assertThat(result.previousDaily().subList(1, 4)).allSatisfy(day -> {
            assertThat(day.netSales()).isEqualByComparingTo("0");
            assertThat(day.operationCount()).isZero();
        });
        assertThat(result.topProducts()).containsExactly(product);
    }

    @Test
    void keepsNegativeNetSalesAndRoundsNegativeAverageHalfUp() {
        var fixture = fixture();
        var day = LocalDate.of(2026, 9, 16);
        when(fixture.sales().daily(fixture.companyId(), fixture.store().getId(), null, day.minusDays(1), day))
                .thenReturn(List.of(new GestionSalesOverviewRepository.DayAggregate(day, new BigDecimal("-10.01"), 2)));

        var result = fixture.service().overview(day, day, null);

        assertThat(result.current()).isEqualTo(new GestionSalesOverviewService.Metrics(
                new BigDecimal("-10.01"), 2, new BigDecimal("-5.01")));
        assertThat(result.daily()).singleElement().satisfies(row -> {
            assertThat(row.netSales()).isEqualByComparingTo("-10.01");
            assertThat(row.operationCount()).isEqualTo(2);
        });
    }

    @Test
    void returns366ZeroDaysInBothPeriodsAndZeroAverageWhenThereAreNoOperations() {
        var fixture = fixture();
        var from = LocalDate.of(2024, 1, 1);

        var result = fixture.service().overview(from, from.plusDays(365), null);

        assertThat(result.daily()).hasSize(366);
        assertThat(result.previousDaily()).hasSize(366);
        assertThat(result.daily().getFirst().date()).isEqualTo(from);
        assertThat(result.daily().getLast().date()).isEqualTo(LocalDate.of(2024, 12, 31));
        assertThat(result.previousDaily().getFirst().date()).isEqualTo(from.minusDays(366));
        assertThat(result.previousDaily().getLast().date()).isEqualTo(from.minusDays(1));
        assertThat(result.current()).isEqualTo(new GestionSalesOverviewService.Metrics(
                new BigDecimal("0.00"), 0, new BigDecimal("0.00")));
        assertThat(result.previous()).isEqualTo(result.current());
        assertThat(result.daily()).allSatisfy(day -> {
            assertThat(day.netSales()).isEqualByComparingTo("0");
            assertThat(day.operationCount()).isZero();
        });
        assertThat(result.topProducts()).isEmpty();
        verifyNoInteractions(fixture.warehouses());
    }

    @Test
    void calculatesAnImmediatelyPrecedingPeriodOfTheSameInclusiveLengthAcrossLeapDay() {
        var period = GestionSalesOverviewService.period(LocalDate.of(2024, 2, 28), LocalDate.of(2024, 3, 1));
        assertThat(period.previousFrom()).isEqualTo(LocalDate.of(2024, 2, 25));
        assertThat(period.previousTo()).isEqualTo(LocalDate.of(2024, 2, 27));
        var singleDay = GestionSalesOverviewService.period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1));
        assertThat(singleDay.previousFrom()).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(singleDay.previousTo()).isEqualTo(singleDay.previousFrom());
    }

    @Test
    void accepts366DaysAndRejectsMissingReversedOrLongerRangesBeforeReadingData() {
        var fixture = fixture();
        var from = LocalDate.of(2024, 1, 1);
        assertThat(GestionSalesOverviewService.period(from, from.plusDays(365)).previousFrom())
                .isEqualTo(from.minusDays(366));
        for (var dates : new LocalDate[][]{
                {null, from}, {from, null}, {from, from.minusDays(1)},
                {from, from.plusDays(366)}, {LocalDate.MIN, LocalDate.MIN},
                {LocalDate.of(1, 1, 1), LocalDate.of(1, 1, 1)},
                {LocalDate.of(10000, 1, 1), LocalDate.of(10000, 1, 1)}}) {
            assertThatThrownBy(() -> fixture.service().readActivity(dates[0], dates[1], null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(fixture.sales(), fixture.warehouses(), fixture.organization());
    }

    @Test
    void scopesReadsToCurrentCompanyAndStoreAndFiltersTheValidatedWarehouse() {
        var fixture = fixture();
        when(fixture.store().getMoneda()).thenReturn("USD");
        var warehouseId = UUID.randomUUID();
        when(fixture.warehouses().findByStoreIdAndIdIn(fixture.store().getId(), List.of(warehouseId)))
                .thenReturn(List.of(mock(Warehouse.class)));
        var from = LocalDate.of(2026, 9, 1);
        var to = LocalDate.of(2026, 9, 3);

        var result = fixture.service().readActivity(from, to, warehouseId);

        assertThat(result.storeTimezone()).isEqualTo("Atlantic/Canary");
        assertThat(result.currency()).isEqualTo("EUR");
        verify(fixture.sales()).daily(fixture.companyId(), fixture.store().getId(), warehouseId,
                LocalDate.of(2026, 8, 29), to);
        verify(fixture.sales()).topProducts(fixture.companyId(), fixture.store().getId(), warehouseId, from, to);
    }

    @Test
    void rejectsUnknownOrForeignWarehouseWithoutQueryingSales() {
        var fixture = fixture();
        var day = LocalDate.of(2026, 9, 16);
        assertThatThrownBy(() -> fixture.service().readActivity(day, day, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("message.warehouse.not_found");
        verifyNoInteractions(fixture.sales());
    }

    private static Fixture fixture() {
        var sales = mock(GestionSalesOverviewRepository.class);
        var warehouses = mock(WarehouseRepository.class);
        var organization = mock(CurrentOrganization.class);
        var company = mock(Company.class);
        var companyId = UUID.randomUUID();
        var store = mock(Store.class);
        when(company.getId()).thenReturn(companyId);
        when(store.getId()).thenReturn(UUID.randomUUID());
        when(store.getEmpresa()).thenReturn(company);
        when(store.getTimezone()).thenReturn("Atlantic/Canary");
        when(store.getMoneda()).thenReturn("EUR");
        when(organization.currentStore()).thenReturn(store);
        return new Fixture(new GestionSalesOverviewService(sales, warehouses, organization),
                sales, warehouses, organization, companyId, store);
    }

    private record Fixture(
            GestionSalesOverviewService service, GestionSalesOverviewRepository sales,
            WarehouseRepository warehouses, CurrentOrganization organization, UUID companyId, Store store) {}
}
