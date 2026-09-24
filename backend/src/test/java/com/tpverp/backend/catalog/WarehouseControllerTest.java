package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WarehouseControllerTest {

    @Test
    void displaysCurrentStoreAddressOnlyForGeneralWarehouse() {
        CatalogService service = mock(CatalogService.class);
        UUID storeId = UUID.randomUUID();
        Warehouse general = Warehouse.general(storeId);
        Warehouse reserve = new Warehouse(storeId, "RESERVA", "Calle Norte 8", "Reposición");
        when(service.warehouses()).thenReturn(List.of(general, reserve));
        when(service.currentStoreAddress()).thenReturn("Calle Mayor 12, 35001 Las Palmas, Las Palmas, ES");

        var result = new WarehouseController(service).list();

        assertThat(result).extracting(WarehouseController.WarehouseView::address)
                .containsExactly("Calle Mayor 12, 35001 Las Palmas, Las Palmas, ES", "Calle Norte 8");
        assertThat(result).extracting(WarehouseController.WarehouseView::notes)
                .containsExactly(null, "Reposición");
    }
}
