package com.tpverp.backend.inventory;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_ALMACEN;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_PRODUCTO;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tpverp.backend.shared.api.PagedResult;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WarehouseInputController.class)
@Import(WarehouseInputControllerWebMvcTest.MethodSecurityConfiguration.class)
class WarehouseInputControllerWebMvcTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private WarehouseInputService service;

    @Test
    void listsWarehouseInputsWithLinesAsJson() throws Exception {
        var input = new WarehouseInput(
                UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 7, 14),
                UUID.randomUUID());
        input.replace(
                UUID.randomUUID(), "Proveedor", "Reposicion",
                List.of(new WarehouseInputLineCommand(UUID.randomUUID(), 3)));
        when(service.listPage(500, null)).thenReturn(new PagedResult<>(List.of(WarehouseInputView.from(input)), null, false));

        mvc.perform(get("/api/v1/warehouse-inputs")
                        .param("limit", "500")
                        .with(user("stock").authorities(() -> GESTION_ALMACEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].date").value("2026-07-14"))
                .andExpect(jsonPath("$.items[0].status").value("BORRADOR"))
                .andExpect(jsonPath("$.items[0].lines.length()").value(1))
                .andExpect(jsonPath("$.items[0].lines[0].quantity").value(3))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void rejectsProductManagementWithoutWarehouseManagement() throws Exception {
        mvc.perform(get("/api/v1/warehouse-inputs")
                        .with(user("product").authorities(() -> GESTION_PRODUCTO)))
                .andExpect(status().isForbidden());
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
