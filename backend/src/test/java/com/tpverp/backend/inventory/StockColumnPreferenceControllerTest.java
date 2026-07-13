package com.tpverp.backend.inventory;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.STOCK_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.VENTA;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StockColumnPreferenceController.class)
@Import(StockColumnPreferenceControllerTest.MethodSecurityConfiguration.class)
class StockColumnPreferenceControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private StockColumnPreferenceService service;

    @Test
    void stockReadersCanGetTheirOwnAppPreference() throws Exception {
        when(service.get(eq("venta"), any(Authentication.class))).thenReturn(
                new StockColumnPreferenceService.PreferenceView(
                        "venta",
                        Map.of("stock.current", List.of(new StockColumnSetting("name", 220)))));

        mvc.perform(get("/api/v1/stock/column-preferences/venta")
                        .with(user("cashier").authorities(() -> STOCK_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app").value("venta"))
                .andExpect(jsonPath("$.settings['stock.current'][0].key").value("name"))
                .andExpect(jsonPath("$.settings['stock.current'][0].width").value(220));

        verify(service).get(eq("venta"), any(Authentication.class));
    }

    @Test
    void salesUsersCanReplaceTheirOwnAppPreference() throws Exception {
        when(service.save(eq("venta"), any(), any(Authentication.class))).thenAnswer(call -> {
            var request = call.getArgument(
                    1, StockColumnPreferenceService.SavePreferenceRequest.class);
            return new StockColumnPreferenceService.PreferenceView(
                    request.app(), request.settings());
        });

        mvc.perform(put("/api/v1/stock/column-preferences/venta")
                        .with(user("cashier").authorities(() -> VENTA))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "app":"venta",
                                  "settings":{
                                    "stock.current":[
                                      {"key":"name","width":220},
                                      {"key":"code","width":110}
                                    ]
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app").value("venta"))
                .andExpect(jsonPath("$.settings['stock.current'].length()").value(2));

        verify(service).save(eq("venta"), any(), any(Authentication.class));
    }

    @Test
    void rejectsUnauthenticatedOrUnauthorizedAccess() throws Exception {
        mvc.perform(get("/api/v1/stock/column-preferences/venta"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/stock/column-preferences/venta")
                        .with(user("other")))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsMalformedColumnJsonBeforeCallingTheService() throws Exception {
        mvc.perform(put("/api/v1/stock/column-preferences/venta")
                        .with(user("cashier").authorities(() -> STOCK_READ))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "app":"venta",
                                  "settings":{
                                    "stock.current":[{"key":"name","width":421}]
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
