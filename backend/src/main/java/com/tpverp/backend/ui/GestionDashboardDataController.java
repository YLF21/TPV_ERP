package com.tpverp.backend.ui;

import com.tpverp.backend.document.DailyCommercialReportService;
import com.tpverp.backend.document.Money;
import com.tpverp.backend.inventory.StockTopSalesPeriod;
import com.tpverp.backend.inventory.StockTopSalesService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.promotion.PromotionService;
import com.tpverp.backend.promotion.PromotionStatus;
import com.tpverp.backend.promotion.PromotionType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/gestion/dashboard/data")
public class GestionDashboardDataController {

    private static final String SALES_WIDGET_PERMISSION =
            "(hasRole('ADMIN') or hasAuthority('APP_GESTION_ACCESS'))"
            + " and (hasRole('ADMIN') or hasAuthority('GESTION_VENTAS'))";
    private static final String PRODUCT_WIDGET_PERMISSION =
            "(hasRole('ADMIN') or hasAuthority('APP_GESTION_ACCESS'))"
            + " and (hasRole('ADMIN') or hasAuthority('GESTION_PRODUCTO'))";

    private final DailyCommercialReportService dailyReports;
    private final StockTopSalesService topSales;
    private final PromotionService promotions;
    private final CurrentOrganization organization;
    private final Clock clock;

    public GestionDashboardDataController(
            DailyCommercialReportService dailyReports,
            StockTopSalesService topSales,
            PromotionService promotions,
            CurrentOrganization organization,
            Clock clock) {
        this.dailyReports = dailyReports;
        this.topSales = topSales;
        this.promotions = promotions;
        this.organization = organization;
        this.clock = clock;
    }

    @GetMapping("/sales-today")
    @PreAuthorize(SALES_WIDGET_PERMISSION)
    public SalesTodayView salesToday() {
        var today = businessDate();
        var current = dailyReports.report(today);
        var previous = dailyReports.report(today.minusDays(1));
        return new SalesTodayView(
                today,
                current.invoiced(),
                current.cashInflow(),
                previous.invoiced(),
                percentageChange(current.invoiced(), previous.invoiced()));
    }

    @GetMapping("/top-products")
    @PreAuthorize(SALES_WIDGET_PERMISSION)
    public List<TopProductView> topProducts() {
        return topSales.topSales(StockTopSalesPeriod.DAY, businessDate()).stream()
                .limit(8)
                .map(row -> new TopProductView(
                        row.productId(), row.name(), row.soldQuantity(), row.netAmount()))
                .toList();
    }

    @GetMapping("/active-promotions")
    @PreAuthorize(PRODUCT_WIDGET_PERMISSION)
    public List<ActivePromotionView> activePromotions() {
        var today = businessDate();
        return promotions.list().stream()
                .filter(promotion -> promotion.status() == PromotionStatus.ACTIVE)
                .filter(promotion -> !today.isBefore(promotion.startDate()))
                .filter(promotion -> promotion.endDate() == null || !today.isAfter(promotion.endDate()))
                .map(promotion -> new ActivePromotionView(
                        promotion.id(), promotion.name(), promotion.type(), promotion.endDate()))
                .toList();
    }

    private LocalDate businessDate() {
        var zone = ZoneId.of(organization.currentStore().getTimezone());
        return LocalDate.now(clock.withZone(zone));
    }

    static BigDecimal percentageChange(BigDecimal current, BigDecimal previous) {
        var normalizedCurrent = Money.euros(current == null ? BigDecimal.ZERO : current);
        var normalizedPrevious = Money.euros(previous == null ? BigDecimal.ZERO : previous);
        if (normalizedPrevious.signum() == 0) {
            return null;
        }
        return normalizedCurrent.subtract(normalizedPrevious)
                .multiply(BigDecimal.valueOf(100))
                .divide(normalizedPrevious.abs(), 2, RoundingMode.HALF_UP);
    }

    public record SalesTodayView(
            LocalDate date,
            BigDecimal issuedTotal,
            BigDecimal collectedTotal,
            BigDecimal previousIssuedTotal,
            BigDecimal changePercent) {
    }

    public record TopProductView(
            UUID productId,
            String name,
            BigDecimal soldQuantity,
            BigDecimal netAmount) {
    }

    public record ActivePromotionView(
            UUID id,
            String name,
            PromotionType type,
            LocalDate endDate) {
    }
}
