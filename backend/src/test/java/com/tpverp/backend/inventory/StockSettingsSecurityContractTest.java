package com.tpverp.backend.inventory;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_PRODUCTO;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.WAREHOUSES_MANAGE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StockController.class)
@Import(StockSettingsSecurityContractTest.MethodSecurityConfiguration.class)
class StockSettingsSecurityContractTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private InventoryService inventory;
    @MockitoBean private StockSnapshotRebuildService snapshots;
    @MockitoBean private StockTopSalesService topSales;
    @MockitoBean private StockSalesHistoryService salesHistory;
    @MockitoBean private StockSettingsService settings;

    @Test
    void warehouseManagementCannotChangeInactiveProductSalesPolicy() throws Exception {
        mvc.perform(patch("/api/v1/stock/settings/inactive-product-sales")
                        .with(user("warehouse-manager").authorities(() -> WAREHOUSES_MANAGE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowInactiveProductSales\":true}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(settings);
    }

    @Test
    void productManagementCanChangeInactiveProductSalesPolicy() throws Exception {
        UUID warehouseId = UUID.randomUUID();
        when(settings.updateInactiveProductSales(any(InactiveProductSalesCommand.class)))
                .thenReturn(new StockSettingsView(
                        warehouseId, true, new BigDecimal("5.000"), true, true));

        mvc.perform(patch("/api/v1/stock/settings/inactive-product-sales")
                        .with(user("product-manager").authorities(() -> GESTION_PRODUCTO))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowInactiveProductSales\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowInactiveProductSales").value(true));

        verify(settings).updateInactiveProductSales(
                new InactiveProductSalesCommand(true));
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
