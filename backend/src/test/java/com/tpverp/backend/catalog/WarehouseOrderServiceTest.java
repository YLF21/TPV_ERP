package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.tpverp.backend.inventory.StockLevelRepository;
import com.tpverp.backend.inventory.StockMovementRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WarehouseOrderServiceTest {
    private final UUID storeId = UUID.randomUUID();
    private final WarehouseRepository warehouses = mock(WarehouseRepository.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final StockLevelRepository stock = mock(StockLevelRepository.class);
    private final StockMovementRepository movements = mock(StockMovementRepository.class);
    private CatalogService service;
    private Warehouse general;
    private Warehouse reserve;

    @BeforeEach
    void setUp() {
        var organization = mock(CurrentOrganization.class);
        var store = mock(Store.class);
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
        when(stores.findByIdForUpdate(storeId)).thenReturn(Optional.of(store));
        service = new CatalogService(organization, null, warehouses, null, null, null, null,
                null, stock, movements, null, null, stores, Clock.systemUTC());
        general = Warehouse.general(storeId);
        reserve = new Warehouse(storeId, "RESERVA", "Dirección", "Notas");
        reserve.setDisplayOrder(1);
        when(warehouses.findByStoreIdForUpdate(storeId)).thenReturn(List.of(general, reserve));
    }

    private WarehouseController.OrderItem item(Warehouse warehouse) {
        return new WarehouseController.OrderItem(warehouse.getId(), warehouse.getVersion());
    }

    @Test
    void reordersGeneralAndInactiveRowsWithoutChangingTheirOtherProperties() {
        reserve.deactivate(BigDecimal.ZERO);
        assertThat(service.reorderWarehouses(List.of(item(reserve), item(general))))
                .containsExactly(reserve, general);
        assertThat(general.getDisplayOrder()).isEqualTo(1);
        assertThat(general.isDefaultWarehouse()).isTrue();
        assertThat(general.isActive()).isTrue();
        assertThatThrownBy(() -> general.rename("OTRO")).isInstanceOf(IllegalStateException.class);
        assertThat(reserve.getDisplayOrder()).isZero();
        assertThat(reserve.isActive()).isFalse();
        assertThat(reserve.getAddress()).isEqualTo("Dirección");
        assertThat(reserve.getNotes()).isEqualTo("Notas");
        var order = inOrder(stores, warehouses);
        order.verify(stores).findByIdForUpdate(storeId);
        order.verify(warehouses).findByStoreIdForUpdate(storeId);
        order.verify(warehouses).flush();
        verifyNoInteractions(stock, movements);
    }

    @Test
    void validatesAllVersionsBeforeChangingAnyPosition() {
        assertThatThrownBy(() -> service.reorderWarehouses(List.of(item(reserve),
                new WarehouseController.OrderItem(general.getId(), 99L))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(general.getDisplayOrder()).isZero();
        assertThat(reserve.getDisplayOrder()).isEqualTo(1);
        verify(warehouses, never()).flush();
    }

    @Test
    void rejectsIncompleteListIncludingAnOmittedInactiveWarehouse() {
        reserve.deactivate(BigDecimal.ZERO);
        assertThatThrownBy(() -> service.reorderWarehouses(List.of(item(general))))
                .isInstanceOf(IllegalStateException.class);
        verify(warehouses, never()).flush();
    }

    @Test
    void rejectsDuplicatesAndForeignIds() {
        assertThatThrownBy(() -> service.reorderWarehouses(List.of(item(general), item(general))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reorderWarehouses(List.of(item(general),
                item(Warehouse.general(UUID.randomUUID())))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(warehouses, never()).flush();
    }

    @Test
    void appendsNewWarehousesAfterTheHighestStoredPositionUnderTheSameStoreLock() {
        when(warehouses.findMaxDisplayOrder(storeId)).thenReturn(8);
        when(warehouses.save(any(Warehouse.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var created = service.createWarehouse("ANEXO", "Otra dirección", "Más notas");
        assertThat(created.getDisplayOrder()).isEqualTo(9);
        var order = inOrder(stores, warehouses);
        order.verify(stores).findByIdForUpdate(storeId);
        order.verify(warehouses).existsByStoreIdAndNombreIgnoreCase(storeId, "ANEXO");
        order.verify(warehouses).findMaxDisplayOrder(storeId);
        order.verify(warehouses).save(created);
    }

    @Test
    void listsAllWarehousesInPersistedOrderWithoutPinningGeneral() {
        when(warehouses.findByStoreIdOrderByDisplayOrderAscIdAsc(storeId)).thenReturn(List.of(reserve, general));
        assertThat(service.warehouses()).containsExactly(reserve, general);
    }

    @Test
    void editingAndActivationUseTheSameStoreLock() {
        when(warehouses.findById(reserve.getId())).thenReturn(Optional.of(reserve));
        service.updateWarehouse(reserve.getId(), "RESERVA", "Nueva dirección", "Notas nuevas");
        service.setWarehouseActive(reserve.getId(), true);
        verify(stores, times(2)).findByIdForUpdate(storeId);
        assertThat(reserve.getDisplayOrder()).isEqualTo(1);
    }

    @Test
    void deletionUsesTheSameStoreLockBeforeReadingTheWarehouse() {
        when(warehouses.findById(reserve.getId())).thenReturn(Optional.of(reserve));
        when(stock.sumQuantityByWarehouseId(reserve.getId())).thenReturn(BigDecimal.ZERO);
        service.deleteWarehouse(reserve.getId());
        var order = inOrder(stores, warehouses);
        order.verify(stores).findByIdForUpdate(storeId);
        order.verify(warehouses).findById(reserve.getId());
        order.verify(warehouses).delete(reserve);
    }
}
