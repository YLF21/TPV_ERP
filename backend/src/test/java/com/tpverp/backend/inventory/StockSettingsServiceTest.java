package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StockSettingsServiceTest {

    @Mock private CurrentOrganization organization;
    @Mock private StockSettingsRepository settings;
    @Mock private StockMinimumRepository minimums;
    @Mock private ProductRepository products;
    @Mock private WarehouseRepository warehouses;
    @Mock private WarehouseStockSettingsRepository warehouseSettings;

    private StockSettingsService service;
    private Store store;
    private Warehouse general;
    private Product product;

    @BeforeEach
    void setUp() {
        var address = Map.of(
                "linea1", "Calle 1",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
        store = new Store(
                new Company("B00000000", "Company", address),
                "Store", address, "hash", "Atlantic/Canary", "EUR", "es-ES");
        general = Warehouse.general(store.getId());
        product = new Product(
                store.getId(), UUID.randomUUID(), null, UUID.randomUUID(),
                "Producto", null, BigDecimal.ZERO, true);
        service = new StockSettingsService(
                organization, settings, minimums, products, warehouses);
        lenient().when(organization.currentStore()).thenReturn(store);
        lenient().when(settings.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(minimums.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void persistsDefaultsForStoreOnFirstRead() {
        when(settings.findById(store.getId())).thenReturn(Optional.empty());
        when(warehouses.findByStoreIdAndPredeterminadoTrue(store.getId()))
                .thenReturn(Optional.of(general));

        var view = service.settings();

        assertThat(view.defaultWarehouseId()).isEqualTo(general.getId());
        assertThat(view.allowNegativeStock()).isTrue();
        assertThat(view.defaultMinimumStock()).isEqualByComparingTo("5.000");
        assertThat(view.alertsEnabled()).isTrue();
        assertThat(view.allowInactiveProductSales()).isFalse();
        verify(settings).save(any(StockSettings.class));
    }

    @Test
    void updatesStoreSettingsWithDecimalMinimum() {
        var secondary = new Warehouse(store.getId(), "SECUNDARIO");
        var current = new StockSettings(store.getId(), general.getId());
        when(warehouses.findById(secondary.getId())).thenReturn(Optional.of(secondary));
        when(settings.findById(store.getId())).thenReturn(Optional.of(current));

        var view = service.updateSettings(new StockSettingsCommand(
                secondary.getId(), false, new BigDecimal("2.500"), false));

        assertThat(view.defaultWarehouseId()).isEqualTo(secondary.getId());
        assertThat(view.allowNegativeStock()).isFalse();
        assertThat(view.defaultMinimumStock()).isEqualByComparingTo("2.500");
        assertThat(view.alertsEnabled()).isFalse();
        assertThat(view.allowInactiveProductSales()).isFalse();
    }

    @Test
    void updatesAndReadsInactiveProductSalePolicy() {
        var current = new StockSettings(store.getId(), general.getId());
        when(settings.findById(store.getId())).thenReturn(Optional.of(current));

        var view = service.updateInactiveProductSales(
                new InactiveProductSalesCommand(true));

        assertThat(view.allowInactiveProductSales()).isTrue();
        assertThat(service.allowsInactiveProductSales(store.getId())).isTrue();
    }

    @Test
    void generalSettingsUpdatePreservesInactiveProductSalePolicy() {
        var current = new StockSettings(store.getId(), general.getId());
        current.setAllowInactiveProductSales(true);
        when(warehouses.findById(general.getId())).thenReturn(Optional.of(general));
        when(settings.findById(store.getId())).thenReturn(Optional.of(current));

        var view = service.updateSettings(new StockSettingsCommand(
                general.getId(), false, new BigDecimal("3.000"), false));

        assertThat(view.allowInactiveProductSales()).isTrue();
    }

    @Test
    void warehousePoliciesAreIndependent() {
        service.setWarehouseSettings(warehouseSettings);
        var secondary = new Warehouse(store.getId(), "SECUNDARIO");
        var generalPolicy = new WarehouseStockSettings(general.getId(), store.getId(), false,
                new BigDecimal("2.000"), false);
        var secondaryPolicy = new WarehouseStockSettings(secondary.getId(), store.getId(), true,
                new BigDecimal("7.000"), true);
        when(warehouseSettings.findByWarehouseIdAndStoreId(general.getId(), store.getId()))
                .thenReturn(Optional.of(generalPolicy));
        when(warehouseSettings.findByWarehouseIdAndStoreId(secondary.getId(), store.getId()))
                .thenReturn(Optional.of(secondaryPolicy));

        assertThat(service.allowsNegativeStock(general.getId(), store.getId())).isFalse();
        assertThat(service.allowsNegativeStock(secondary.getId(), store.getId())).isTrue();
    }

    @Test
    void globalUpdateChangesOnlyWarehousesStillInheriting() {
        service.setWarehouseSettings(warehouseSettings);
        var inherited = new WarehouseStockSettings(general.getId(), store.getId(), true,
                new BigDecimal("5.000"), true);
        var customized = new WarehouseStockSettings(UUID.randomUUID(), store.getId(), true,
                new BigDecimal("5.000"), true);
        customized.update(false, new BigDecimal("1.000"), false);
        var current = new StockSettings(store.getId(), general.getId());
        when(warehouses.findById(general.getId())).thenReturn(Optional.of(general));
        when(settings.findById(store.getId())).thenReturn(Optional.of(current));
        when(warehouseSettings.findByStoreIdAndInheritsStoreSettingsTrue(store.getId()))
                .thenReturn(List.of(inherited));

        service.updateSettings(new StockSettingsCommand(general.getId(), false,
                new BigDecimal("3.000"), false));

        assertThat(inherited.isAllowNegativeStock()).isFalse();
        assertThat(inherited.getDefaultMinimumStock()).isEqualByComparingTo("3.000");
        assertThat(inherited.isInheritsStoreSettings()).isTrue();
        assertThat(customized.getDefaultMinimumStock()).isEqualByComparingTo("1.000");
        assertThat(customized.isInheritsStoreSettings()).isFalse();
    }

    @Test
    void bulkWarehouseUpdateIncludesCustomOverridesAndPreservesStoreOnlyPolicy() {
        service.setWarehouseSettings(warehouseSettings);
        var secondary = new Warehouse(store.getId(), "SECUNDARIO");
        var inherited = new WarehouseStockSettings(general.getId(), store.getId(), true,
                new BigDecimal("5.000"), true);
        var customized = new WarehouseStockSettings(secondary.getId(), store.getId(), false,
                new BigDecimal("1.000"), false);
        customized.update(false, new BigDecimal("1.000"), false);
        var current = new StockSettings(store.getId(), general.getId());
        current.setAllowInactiveProductSales(true);
        when(settings.findById(store.getId())).thenReturn(Optional.of(current));
        when(warehouseSettings.findByStoreId(store.getId())).thenReturn(List.of(inherited, customized));
        when(warehouses.findByStoreIdOrderByNombre(store.getId())).thenReturn(List.of(general, secondary));

        var result = service.applyWarehouseSettingsToAll(new WarehouseStockSettingsCommand(
                false, new BigDecimal("3.500"), true));

        assertThat(result.defaultWarehouseId()).isEqualTo(general.getId());
        assertThat(result.allowInactiveProductSales()).isTrue();
        assertThat(result.defaultMinimumStock()).isEqualByComparingTo("3.500");
        for (var policy : List.of(inherited, customized)) {
            assertThat(policy.isAllowNegativeStock()).isFalse();
            assertThat(policy.getDefaultMinimumStock()).isEqualByComparingTo("3.500");
            assertThat(policy.isAlertsEnabled()).isTrue();
            assertThat(policy.isInheritsStoreSettings()).isTrue();
        }
        verify(warehouseSettings).saveAll(any());
    }

    @Test
    void changingDefaultWarehouseKeepsExistingGlobalSettings() {
        var secondary = new Warehouse(store.getId(), "SECUNDARIO");
        var current = new StockSettings(store.getId(), general.getId());
        current.update(general.getId(), false, new BigDecimal("3.500"), false);
        current.setAllowInactiveProductSales(true);
        when(warehouses.findById(secondary.getId())).thenReturn(Optional.of(secondary));
        when(settings.findById(store.getId())).thenReturn(Optional.of(current));

        var result = service.updateDefaultWarehouse(secondary.getId());

        assertThat(result.defaultWarehouseId()).isEqualTo(secondary.getId());
        assertThat(result.allowNegativeStock()).isFalse();
        assertThat(result.defaultMinimumStock()).isEqualByComparingTo("3.500");
        assertThat(result.alertsEnabled()).isFalse();
        assertThat(result.allowInactiveProductSales()).isTrue();
    }

    @Test
    void minimumFallsBackToStoreDefaultWhenNoOverrideExists() {
        var current = new StockSettings(store.getId(), general.getId());
        current.update(general.getId(), true, new BigDecimal("7.250"), true);
        validReferences();
        when(minimums.findByStoreIdAndProductIdAndWarehouseId(
                store.getId(), product.getId(), general.getId()))
                .thenReturn(Optional.empty());
        when(settings.findById(store.getId())).thenReturn(Optional.of(current));

        var view = service.minimum(product.getId(), general.getId());

        assertThat(view.minimumStock()).isEqualByComparingTo("7.250");
        assertThat(view.overridden()).isFalse();
    }

    @Test
    void createsAndDeletesWarehouseSpecificMinimum() {
        validReferences();
        when(minimums.findByStoreIdAndProductIdAndWarehouseId(
                store.getId(), product.getId(), general.getId()))
                .thenReturn(Optional.empty());

        var view = service.updateMinimum(
                product.getId(), general.getId(),
                new StockMinimumCommand(new BigDecimal("1.125")));

        assertThat(view.minimumStock()).isEqualByComparingTo("1.125");
        assertThat(view.overridden()).isTrue();

        var saved = new StockMinimum(
                store.getId(), product.getId(), general.getId(), new BigDecimal("1.125"));
        when(minimums.findByStoreIdAndProductIdAndWarehouseId(
                store.getId(), product.getId(), general.getId()))
                .thenReturn(Optional.of(saved));

        service.deleteMinimum(product.getId(), general.getId());

        verify(minimums).delete(saved);
    }

    @Test
    void rejectsMinimumWithMoreThanThreeDecimals() {
        validReferences();
        when(minimums.findByStoreIdAndProductIdAndWarehouseId(
                store.getId(), product.getId(), general.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateMinimum(
                product.getId(), general.getId(),
                new StockMinimumCommand(new BigDecimal("1.0001"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3 decimales");
    }

    @Test
    void rejectsDefaultWarehouseFromAnotherStore() {
        var foreignWarehouse = new Warehouse(UUID.randomUUID(), "AJENO");
        when(warehouses.findById(foreignWarehouse.getId()))
                .thenReturn(Optional.of(foreignWarehouse));

        assertThatThrownBy(() -> service.updateSettings(new StockSettingsCommand(
                foreignWarehouse.getId(), false, new BigDecimal("2.000"), true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message.warehouse.not_available_for_store");

        verify(settings, never()).save(any());
    }

    @Test
    void rejectsMinimumWhenProductAndWarehouseDoNotBelongToCurrentStore() {
        var foreignProduct = new Product(
                UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(),
                "Ajeno", null, BigDecimal.ZERO, true);
        when(products.findById(foreignProduct.getId())).thenReturn(Optional.of(foreignProduct));

        assertThatThrownBy(() -> service.updateMinimum(
                foreignProduct.getId(), general.getId(),
                new StockMinimumCommand(new BigDecimal("1.000"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message.product.not_available_for_store");

        verify(minimums, never()).save(any());
    }

    private void validReferences() {
        lenient().when(products.findById(product.getId())).thenReturn(Optional.of(product));
        lenient().when(warehouses.findById(general.getId())).thenReturn(Optional.of(general));
    }
}
