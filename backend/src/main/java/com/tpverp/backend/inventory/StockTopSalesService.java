package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.Family;
import com.tpverp.backend.catalog.FamilyRepository;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.ProductSupplierRepository;
import com.tpverp.backend.catalog.Subfamily;
import com.tpverp.backend.catalog.SubfamilyRepository;
import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentLine;
import com.tpverp.backend.document.Money;
import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockTopSalesService {

    private static final Set<CommercialDocumentType> SALE_TYPES = Set.of(
            CommercialDocumentType.TICKET,
            CommercialDocumentType.ALBARAN_VENTA,
            CommercialDocumentType.FACTURA_VENTA,
            CommercialDocumentType.RECTIFICATIVA_VENTA);

    private final CurrentOrganization organization;
    private final CommercialDocumentRepository documents;
    private final ProductRepository products;
    private final FamilyRepository families;
    private final SubfamilyRepository subfamilies;
    private final ProductSupplierRepository productSuppliers;
    private final StockLevelRepository stocks;
    private final WarehouseRepository warehouses;

    public StockTopSalesService(
            CurrentOrganization organization,
            CommercialDocumentRepository documents,
            ProductRepository products,
            FamilyRepository families,
            SubfamilyRepository subfamilies,
            ProductSupplierRepository productSuppliers,
            StockLevelRepository stocks,
            WarehouseRepository warehouses) {
        this.organization = organization;
        this.documents = documents;
        this.products = products;
        this.families = families;
        this.subfamilies = subfamilies;
        this.productSuppliers = productSuppliers;
        this.stocks = stocks;
        this.warehouses = warehouses;
    }

    @Transactional(readOnly = true)
    public List<StockTopSalesRow> topSales(StockTopSalesPeriod period, LocalDate date) {
        return topSales(period, date, null);
    }

    @Transactional(readOnly = true)
    public List<StockTopSalesRow> topSales(
            StockTopSalesPeriod period, LocalDate date, UUID warehouseId) {
        var selectedDate = Objects.requireNonNull(date, "date");
        var selectedPeriod = period == null ? StockTopSalesPeriod.WEEK : period;
        return topSales(selectedPeriod.startDate(selectedDate), selectedDate, warehouseId);
    }

    @Transactional(readOnly = true)
    public List<StockTopSalesRow> topSales(LocalDate dateFrom, LocalDate dateTo) {
        return topSales(dateFrom, dateTo, null);
    }

    @Transactional(readOnly = true)
    public List<StockTopSalesRow> topSales(
            LocalDate dateFrom, LocalDate dateTo, UUID warehouseId) {
        var selectedFrom = Objects.requireNonNull(dateFrom, "dateFrom");
        var selectedTo = Objects.requireNonNull(dateTo, "dateTo");
        var startDate = selectedFrom.isAfter(selectedTo) ? selectedTo : selectedFrom;
        var endDate = selectedFrom.isAfter(selectedTo) ? selectedFrom : selectedTo;
        var store = organization.currentStore();
        var totals = new HashMap<ProductWarehouse, Totals>();
        documents.findTopSalesDocuments(
                        store.getId(),
                        startDate,
                        endDate,
                        SALE_TYPES,
                        warehouseId)
                .forEach(document -> accumulate(totals, document));

        var positiveKeys = totals.entrySet().stream()
                .filter(entry -> entry.getValue().soldQuantity.signum() > 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (positiveKeys.isEmpty()) {
            return List.of();
        }

        var positiveProductIds = positiveKeys.stream()
                .map(ProductWarehouse::productId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        var productsById = products.findAllByStoreIdAndIdIn(store.getId(), positiveProductIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        var familyIds = productsById.values().stream()
                .map(Product::getFamilyId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        var subfamilyIds = productsById.values().stream()
                .map(Product::getSubfamilyId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        var familiesById = families.findAllById(familyIds).stream()
                .collect(Collectors.toMap(Family::getId, Family::getName));
        var subfamiliesById = subfamilies.findAllById(subfamilyIds).stream()
                .collect(Collectors.toMap(Subfamily::getId, Subfamily::getName));
        var warehouseIds = positiveKeys.stream()
                .map(ProductWarehouse::warehouseId)
                .collect(Collectors.toSet());
        var warehousesById = warehouses.findAllById(warehouseIds).stream()
                .collect(Collectors.toMap(Warehouse::getId, Warehouse::getName));

        return positiveKeys.stream()
                .map(key -> row(store.getId(), key, productsById.get(key.productId()), totals.get(key),
                        familiesById, subfamiliesById, warehousesById))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(StockTopSalesRow::soldQuantity).reversed()
                        .thenComparing(StockTopSalesRow::name)
                        .thenComparing(StockTopSalesRow::warehouseName))
                .toList();
    }

    private void accumulate(Map<ProductWarehouse, Totals> totals, CommercialDocument document) {
        var lines = document.getLineas();
        var netAmounts = proratedNetAmounts(document, lines);
        for (int index = 0; index < lines.size(); index++) {
            DocumentLine line = lines.get(index);
            if (line.getProductoId() == null) {
                continue;
            }
            var key = new ProductWarehouse(line.getProductoId(), document.getAlmacenId());
            totals.computeIfAbsent(key, ignored -> new Totals())
                    .add(line.getCantidad(), netAmounts.get(index));
        }
    }

    private static List<BigDecimal> proratedNetAmounts(
            CommercialDocument document, List<DocumentLine> lines) {
        var factor = BigDecimal.ONE.subtract(document.getDescuentoGlobal().movePointLeft(2));
        var rawAmounts = lines.stream()
                .map(line -> line.getTotal().multiply(factor))
                .toList();
        var allocated = rawAmounts.stream()
                .map(Money::euros)
                .collect(Collectors.toCollection(ArrayList::new));
        var allocatedTotal = allocated.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int remainingCents = document.getTotal()
                .subtract(allocatedTotal)
                .movePointRight(2)
                .intValueExact();
        if (remainingCents == 0 || lines.isEmpty()) {
            return allocated;
        }

        Comparator<Integer> byRoundingRemainder = Comparator.comparing(
                index -> rawAmounts.get(index).subtract(allocated.get(index)));
        if (remainingCents > 0) {
            byRoundingRemainder = byRoundingRemainder.reversed();
        }
        var allocationOrder = IntStream.range(0, lines.size()).boxed()
                .sorted(byRoundingRemainder.thenComparingInt(Integer::intValue))
                .toList();
        var cent = new BigDecimal(remainingCents > 0 ? "0.01" : "-0.01");
        for (int index = 0; index < Math.abs(remainingCents); index++) {
            int lineIndex = allocationOrder.get(index % allocationOrder.size());
            allocated.set(lineIndex, allocated.get(lineIndex).add(cent));
        }
        return allocated;
    }

    private StockTopSalesRow row(
            UUID storeId,
            ProductWarehouse key,
            Product product,
            Totals totals,
            Map<UUID, String> familiesById,
            Map<UUID, String> subfamiliesById,
            Map<UUID, String> warehousesById) {
        if (product == null) {
            return null;
        }
        var productId = key.productId();
        return new StockTopSalesRow(
                productId,
                text(product.getCode()),
                text(product.getBarcode()),
                text(product.getName()),
                product.getFamilyId(),
                text(familiesById.get(product.getFamilyId())),
                product.getSubfamilyId(),
                text(subfamiliesById.get(product.getSubfamilyId())),
                productSuppliers.findForProduct(productId, storeId).stream()
                        .map(link -> new StockTopSalesSupplierView(
                                link.getSupplier().getId(),
                                text(link.getSupplier().getSupplierId()),
                                text(link.getSupplier().getLegalName())))
                        .toList(),
                quantity(totals.soldQuantity),
                Money.euros(totals.netAmount),
                quantity(stocks.findByProductIdAndWarehouseId(productId, key.warehouseId())
                        .map(StockLevel::getQuantity)
                        .orElse(BigDecimal.ZERO)),
                key.warehouseId(),
                text(warehousesById.get(key.warehouseId())));
    }

    private static BigDecimal quantity(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(3, Money.ROUNDING);
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static final class Totals {
        private BigDecimal soldQuantity = BigDecimal.ZERO.setScale(3, Money.ROUNDING);
        private BigDecimal netAmount = Money.euros(BigDecimal.ZERO);

        private void add(BigDecimal quantity, BigDecimal amount) {
            soldQuantity = StockTopSalesService.quantity(soldQuantity.add(quantity));
            netAmount = Money.euros(netAmount.add(amount));
        }
    }

    private record ProductWarehouse(UUID productId, UUID warehouseId) {
    }
}
