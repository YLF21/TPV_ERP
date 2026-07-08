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
        var selectedDate = Objects.requireNonNull(date, "date");
        var selectedPeriod = period == null ? StockTopSalesPeriod.WEEK : period;
        return topSales(selectedPeriod.startDate(selectedDate), selectedDate);
    }

    @Transactional(readOnly = true)
    public List<StockTopSalesRow> topSales(LocalDate dateFrom, LocalDate dateTo) {
        var selectedFrom = Objects.requireNonNull(dateFrom, "dateFrom");
        var selectedTo = Objects.requireNonNull(dateTo, "dateTo");
        var startDate = selectedFrom.isAfter(selectedTo) ? selectedTo : selectedFrom;
        var endDate = selectedFrom.isAfter(selectedTo) ? selectedFrom : selectedTo;
        var store = organization.currentStore();
        var totals = new HashMap<UUID, Totals>();
        documents.findTopSalesDocuments(
                        store.getId(),
                        startDate,
                        endDate,
                        SALE_TYPES)
                .forEach(document -> document.getLineas().forEach(line -> accumulate(totals, line)));

        var positiveProductIds = totals.entrySet().stream()
                .filter(entry -> entry.getValue().soldQuantity.signum() > 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (positiveProductIds.isEmpty()) {
            return List.of();
        }

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

        return positiveProductIds.stream()
                .map(productId -> row(store.getId(), productId, productsById.get(productId), totals.get(productId),
                        familiesById, subfamiliesById))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(StockTopSalesRow::soldQuantity).reversed()
                        .thenComparing(StockTopSalesRow::name))
                .toList();
    }

    private void accumulate(Map<UUID, Totals> totals, DocumentLine line) {
        totals.computeIfAbsent(line.getProductoId(), ignored -> new Totals())
                .add(line.getCantidad(), line.getTotal());
    }

    private StockTopSalesRow row(
            UUID storeId,
            UUID productId,
            Product product,
            Totals totals,
            Map<UUID, String> familiesById,
            Map<UUID, String> subfamiliesById) {
        if (product == null) {
            return null;
        }
        var warehouse = warehouse(productId);
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
                quantity(stocks.sumQuantityByProductId(productId)),
                warehouse.id(),
                warehouse.name());
    }

    private WarehouseSummary warehouse(UUID productId) {
        var stockRows = stocks.findByProductId(productId);
        var warehouseIds = stockRows.stream()
                .map(StockLevel::getWarehouseId)
                .distinct()
                .toList();
        if (warehouseIds.isEmpty()) {
            return new WarehouseSummary(null, "-");
        }
        if (warehouseIds.size() > 1) {
            return new WarehouseSummary(null, "Varios");
        }
        var warehouseId = warehouseIds.getFirst();
        var warehouseName = warehouses.findAllById(List.of(warehouseId)).stream()
                .findFirst()
                .map(Warehouse::getName)
                .orElse("-");
        return new WarehouseSummary(warehouseId, warehouseName);
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

    private record WarehouseSummary(UUID id, String name) {
    }
}
