package com.tpverp.backend.catalog;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WarehouseController.class)
@Import(WarehouseOrderControllerWebMvcTest.MethodSecurityConfiguration.class)
class WarehouseOrderControllerWebMvcTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private CatalogService service;

    @Test
    void warehouseManagementPermissionAloneCanLoadTheOrderedList() throws Exception {
        var warehouse = new Warehouse(UUID.randomUUID(), "RESERVA");
        warehouse.setDisplayOrder(2);
        when(service.warehouses()).thenReturn(List.of(warehouse));

        mvc.perform(get("/api/v1/warehouses")
                        .with(user("manager").authorities(() -> WAREHOUSES_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(warehouse.getId().toString()))
                .andExpect(jsonPath("$[0].displayOrder").value(2))
                .andExpect(jsonPath("$[0].version").value(0));
    }

    @Test
    void unrelatedPermissionCannotLoadTheWarehouseList() throws Exception {
        mvc.perform(get("/api/v1/warehouses")
                        .with(user("reader").authorities(() -> CUSTOMERS_READ)))
                .andExpect(status().isForbidden());
        verify(service, never()).warehouses();
    }

    @Test
    void acceptsExistingManagementPermissionsAndReturnsOrderAndVersions() throws Exception {
        var reserve = new Warehouse(UUID.randomUUID(), "RESERVA");
        var general = Warehouse.general(reserve.getStoreId());
        general.setDisplayOrder(1);
        when(service.reorderWarehouses(any())).thenReturn(List.of(reserve, general));
        when(service.currentStoreAddress()).thenReturn("Dirección tienda");
        for (String permission : List.of(WAREHOUSES_MANAGE, GESTION_ALMACEN)) {
            mvc.perform(put("/api/v1/warehouses/order")
                            .with(user("manager").authorities(() -> permission)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"warehouses":[{"id":"%s","version":0},{"id":"%s","version":0}]}
                                    """.formatted(reserve.getId(), general.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(reserve.getId().toString()))
                    .andExpect(jsonPath("$[1].defaultWarehouse").value(true))
                    .andExpect(jsonPath("$[1].address").value("Dirección tienda"))
                    .andExpect(jsonPath("$[1].displayOrder").value(1))
                    .andExpect(jsonPath("$[1].version").value(0));
        }
    }

    @Test
    void readOnlyPermissionCannotReorder() throws Exception {
        mvc.perform(put("/api/v1/warehouses/order")
                        .with(user("reader").authorities(() -> STOCK_READ)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouses\":[{\"id\":\"" + UUID.randomUUID() + "\",\"version\":0}]}"))
                .andExpect(status().isForbidden());
        verify(service, never()).reorderWarehouses(any());
    }

    @Test
    void validatesMissingVersionNegativeVersionNullRowsAndEmptyList() throws Exception {
        for (String body : List.of("{\"warehouses\":[]}", "{\"warehouses\":[null]}",
                "{\"warehouses\":[{\"id\":\"" + UUID.randomUUID() + "\"}]}",
                "{\"warehouses\":[{\"id\":\"" + UUID.randomUUID() + "\",\"version\":-1}]}")) {
            mvc.perform(put("/api/v1/warehouses/order").with(user("admin").roles("ADMIN")).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(service, never()).reorderWarehouses(any());
    }

    @Test
    void staleListReturnsExistingStateConflictContract() throws Exception {
        when(service.reorderWarehouses(any())).thenThrow(new IllegalStateException("Listado modificado"));
        mvc.perform(put("/api/v1/warehouses/order").with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouses\":[{\"id\":\"" + UUID.randomUUID() + "\",\"version\":0}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration { }
}
