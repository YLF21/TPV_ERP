package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.DiscountType;
import com.tpverp.backend.catalog.Family;
import com.tpverp.backend.catalog.FamilyRepository;
import com.tpverp.backend.catalog.IdentifierType;
import com.tpverp.backend.catalog.PriceTier;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.ProductSupplier;
import com.tpverp.backend.catalog.ProductSupplierRepository;
import com.tpverp.backend.catalog.ProductType;
import com.tpverp.backend.catalog.Subfamily;
import com.tpverp.backend.catalog.SubfamilyRepository;
import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentLine;
import com.tpverp.backend.document.Money;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.DocumentType;
import com.tpverp.backend.party.Supplier;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockTopSalesServiceTest {

    @Test
    void ranksProductsByPositiveNetUnitsForMovingPeriod() {
        var fixture = fixture();
        var productA = product(fixture.store().getId(), "A001", "8430000000011", "Cafe", fixture.family(), fixture.subfamily());
        var productB = product(fixture.store().getId(), "B002", "8430000000028", "Pan", fixture.family(), null);
        var productC = product(fixture.store().getId(), "C003", null, "Aceite", fixture.family(), null);
        var warehouse = Warehouse.general(fixture.store().getId());
        var supplier = supplier(fixture.company());
        var sale = document(fixture.store().getId(), warehouse.getId(), LocalDate.of(2026, 7, 8),
                line(productA, 2, "3.00"),
                line(productB, 1, "5.00"),
                line(productC, 1, "7.00"));
        var previousSale = document(fixture.store().getId(), warehouse.getId(), LocalDate.of(2026, 7, 2),
                line(productA, 4, "3.00"),
                line(productB, 1, "5.00"));
        var returnSale = document(fixture.store().getId(), warehouse.getId(), LocalDate.of(2026, 7, 8),
                line(productA, -1, "3.00"),
                line(productC, -1, "7.00"));
        when(fixture.documents().findTopSalesDocuments(
                fixture.store().getId(),
                LocalDate.of(2026, 7, 2),
                LocalDate.of(2026, 7, 8),
                Set.of(
                        CommercialDocumentType.TICKET,
                        CommercialDocumentType.ALBARAN_VENTA,
                        CommercialDocumentType.FACTURA_VENTA,
                        CommercialDocumentType.RECTIFICATIVA_VENTA)))
                .thenReturn(List.of(sale, previousSale, returnSale));
        when(fixture.products().findAllByStoreIdAndIdIn(
                fixture.store().getId(),
                Set.of(productA.getId(), productB.getId())))
                .thenReturn(List.of(productA, productB));
        when(fixture.families().findAllById(Set.of(fixture.family().getId())))
                .thenReturn(List.of(fixture.family()));
        when(fixture.subfamilies().findAllById(Set.of(fixture.subfamily().getId())))
                .thenReturn(List.of(fixture.subfamily()));
        when(fixture.productSuppliers().findForProduct(productA.getId(), fixture.store().getId()))
                .thenReturn(List.of(new ProductSupplier(productA, supplier, "SUP-A")));
        when(fixture.productSuppliers().findForProduct(productB.getId(), fixture.store().getId()))
                .thenReturn(List.of());
        when(fixture.stocks().sumQuantityByProductId(productA.getId()))
                .thenReturn(new BigDecimal("14.000"));
        when(fixture.stocks().sumQuantityByProductId(productB.getId()))
                .thenReturn(new BigDecimal("2.000"));
        when(fixture.stocks().findByProductId(productA.getId()))
                .thenReturn(List.of(new StockLevel(productA.getId(), warehouse.getId())));
        when(fixture.stocks().findByProductId(productB.getId()))
                .thenReturn(List.of(new StockLevel(productB.getId(), warehouse.getId())));
        when(fixture.warehouses().findAllById(List.of(warehouse.getId())))
                .thenReturn(List.of(warehouse));

        var rows = fixture.service().topSales(StockTopSalesPeriod.WEEK, LocalDate.of(2026, 7, 8));

        assertThat(rows).extracting(StockTopSalesRow::code).containsExactly("A001", "B002");
        assertThat(rows.get(0).soldQuantity()).isEqualByComparingTo("5.000");
        assertThat(rows.get(0).netAmount()).isEqualByComparingTo("15.00");
        assertThat(rows.get(0).familyName()).isEqualTo("BEBIDAS");
        assertThat(rows.get(0).subfamilyName()).isEqualTo("CAFE");
        assertThat(rows.get(0).suppliers()).extracting(StockTopSalesSupplierView::supplierCode)
                .containsExactly("PR0001");
        assertThat(rows.get(0).currentStock()).isEqualByComparingTo("14.000");
        assertThat(rows.get(0).warehouseName()).isEqualTo("GENERAL");
    }

    @Test
    void calculatesMovingRangesFromSelectedDate() {
        assertThat(StockTopSalesPeriod.DAY.startDate(LocalDate.of(2026, 7, 8)))
                .isEqualTo(LocalDate.of(2026, 7, 8));
        assertThat(StockTopSalesPeriod.WEEK.startDate(LocalDate.of(2026, 7, 8)))
                .isEqualTo(LocalDate.of(2026, 7, 2));
        assertThat(StockTopSalesPeriod.MONTH.startDate(LocalDate.of(2026, 7, 8)))
                .isEqualTo(LocalDate.of(2026, 6, 9));
        assertThat(StockTopSalesPeriod.YEAR.startDate(LocalDate.of(2026, 7, 8)))
                .isEqualTo(LocalDate.of(2025, 7, 9));
    }

    @Test
    void queriesExplicitDateRangeForCustomTopSales() {
        var fixture = fixture();
        var product = product(fixture.store().getId(), "A001", "8430000000011", "Cafe", fixture.family(), null);
        var warehouse = Warehouse.general(fixture.store().getId());
        var sale = document(fixture.store().getId(), warehouse.getId(), LocalDate.of(2026, 2, 15),
                line(product, 2, "3.00"));
        when(fixture.documents().findTopSalesDocuments(
                fixture.store().getId(),
                LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 2, 28),
                Set.of(
                        CommercialDocumentType.TICKET,
                        CommercialDocumentType.ALBARAN_VENTA,
                        CommercialDocumentType.FACTURA_VENTA,
                        CommercialDocumentType.RECTIFICATIVA_VENTA)))
                .thenReturn(List.of(sale));
        when(fixture.products().findAllByStoreIdAndIdIn(fixture.store().getId(), Set.of(product.getId())))
                .thenReturn(List.of(product));
        when(fixture.families().findAllById(Set.of(fixture.family().getId())))
                .thenReturn(List.of(fixture.family()));
        when(fixture.subfamilies().findAllById(Set.of()))
                .thenReturn(List.of());
        when(fixture.productSuppliers().findForProduct(product.getId(), fixture.store().getId()))
                .thenReturn(List.of());
        when(fixture.stocks().sumQuantityByProductId(product.getId()))
                .thenReturn(new BigDecimal("5.000"));
        when(fixture.stocks().findByProductId(product.getId()))
                .thenReturn(List.of(new StockLevel(product.getId(), warehouse.getId())));
        when(fixture.warehouses().findAllById(List.of(warehouse.getId())))
                .thenReturn(List.of(warehouse));

        var rows = fixture.service().topSales(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));

        assertThat(rows).extracting(StockTopSalesRow::code).containsExactly("A001");
    }

    private static DocumentLine line(Product product, int quantity, String price) {
        return new DocumentLine(
                new CommercialDocument(UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                        LocalDate.now(), UUID.randomUUID(), BigDecimal.ZERO),
                product.getId(), 1, quantity, product.getCode(), product.getName(), "VENTA",
                new BigDecimal(price), BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO);
    }

    private static CommercialDocument document(UUID storeId, UUID warehouseId, LocalDate date, DocumentLine... lines) {
        var document = new CommercialDocument(
                storeId, warehouseId, CommercialDocumentType.TICKET, date, UUID.randomUUID(), BigDecimal.ZERO);
        var position = 1;
        for (var sourceLine : lines) {
            document.addLine(new DocumentLine(
                    document,
                    sourceLine.getProductoId(),
                    position++,
                    sourceLine.getCantidad(),
                    sourceLine.getCodigo(),
                    sourceLine.getNombre(),
                    sourceLine.getTarifa(),
                    sourceLine.getPrecioUnitario(),
                    sourceLine.getDescuento(),
                    sourceLine.isImpuestosIncluidos(),
                    sourceLine.getRegimenImpuesto(),
                    sourceLine.getPorcentajeImpuesto()));
        }
        document.confirm("T-001", UUID.randomUUID(), Instant.now(), true);
        return document;
    }

    private static Product product(UUID storeId, String code, String barcode, String name, Family family, Subfamily subfamily) {
        var product = new Product(
                storeId,
                family.getId(),
                subfamily == null ? null : subfamily.getId(),
                UUID.randomUUID(),
                ProductType.UNIT,
                DiscountType.NORMAL,
                name,
                null,
                null,
                Money.euros(BigDecimal.ONE),
                true);
        product.replaceIdentifier(IdentifierType.CODIGO, code);
        if (barcode != null) {
            product.replaceIdentifier(IdentifierType.CODIGO_BARRAS, barcode);
        }
        product.setPrice(PriceTier.VENTA, Money.euros(BigDecimal.ONE));
        return product;
    }

    private static Supplier supplier(Company company) {
        var supplier = new Supplier(
                company,
                "Proveedor General",
                null,
                DocumentType.NIF,
                "B00000001",
                null,
                null,
                null,
                null);
        supplier.assignCode("PR0001");
        return supplier;
    }

    private static Fixture fixture() {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
        var company = new Company("B00000000", "Company", address);
        var store = new Store(
                company,
                "001",
                "Store",
                address,
                UUID.randomUUID().toString(),
                "Atlantic/Canary",
                "EUR",
                "es-ES");
        var family = new Family(store.getId(), "Bebidas", false);
        var subfamily = new Subfamily(family.getId(), "Cafe");
        var documents = mock(CommercialDocumentRepository.class);
        var products = mock(ProductRepository.class);
        var families = mock(FamilyRepository.class);
        var subfamilies = mock(SubfamilyRepository.class);
        var productSuppliers = mock(ProductSupplierRepository.class);
        var stocks = mock(StockLevelRepository.class);
        var warehouses = mock(WarehouseRepository.class);
        var organization = mock(CurrentOrganization.class);
        when(organization.currentStore()).thenReturn(store);
        return new Fixture(
                new StockTopSalesService(
                        organization,
                        documents,
                        products,
                        families,
                        subfamilies,
                        productSuppliers,
                        stocks,
                        warehouses),
                company,
                store,
                family,
                subfamily,
                documents,
                products,
                families,
                subfamilies,
                productSuppliers,
                stocks,
                warehouses);
    }

    private record Fixture(
            StockTopSalesService service,
            Company company,
            Store store,
            Family family,
            Subfamily subfamily,
            CommercialDocumentRepository documents,
            ProductRepository products,
            FamilyRepository families,
            SubfamilyRepository subfamilies,
            ProductSupplierRepository productSuppliers,
            StockLevelRepository stocks,
            WarehouseRepository warehouses) {
    }
}
