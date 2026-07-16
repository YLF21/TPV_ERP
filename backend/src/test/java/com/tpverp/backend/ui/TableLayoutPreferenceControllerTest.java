package com.tpverp.backend.ui;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TableLayoutPreferenceController.class)
@Import(TableLayoutPreferenceControllerTest.MethodSecurityConfiguration.class)
class TableLayoutPreferenceControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private TableLayoutPreferenceService service;

    @Test
    void anyAuthenticatedUserCanListTheirAppPreferences() throws Exception {
        when(service.list(eq("venta"), any(Authentication.class))).thenReturn(
                new TableLayoutPreferenceService.PreferenceListView("venta", List.of(
                        new TableLayoutPreferenceService.PreferenceView(
                                "venta",
                                "stock.current",
                                List.of(new TableLayoutColumn("name", 220, true))))));

        mvc.perform(get("/api/v1/ui/table-preferences/venta")
                        .with(user("cashier")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app").value("venta"))
                .andExpect(jsonPath("$.preferences[0].app").value("venta"))
                .andExpect(jsonPath("$.preferences[0].tableKey").value("stock.current"))
                .andExpect(jsonPath("$.preferences[0].columns[0].key").value("name"));

        verify(service).list(eq("venta"), any(Authentication.class));
    }

    @Test
    void getsOnePreferenceWithEmptyColumnsWhenMissing() throws Exception {
        when(service.get(eq("gestion"), eq("products.list"), any(Authentication.class)))
                .thenReturn(new TableLayoutPreferenceService.PreferenceView(
                        "gestion", "products.list", List.of()));

        mvc.perform(get("/api/v1/ui/table-preferences/gestion/products.list")
                        .with(user("manager")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app").value("gestion"))
                .andExpect(jsonPath("$.tableKey").value("products.list"))
                .andExpect(jsonPath("$.columns").isEmpty());
    }

    @Test
    void savesAValidatedLayoutAndDefaultsMissingVisibility() throws Exception {
        when(service.save(
                eq("venta"), eq("stock.current"), any(), any(Authentication.class)))
                .thenAnswer(call -> {
                    var request = call.getArgument(
                            2, TableLayoutPreferenceService.SavePreferenceRequest.class);
                    return new TableLayoutPreferenceService.PreferenceView(
                            request.app(), request.tableKey(), request.columns());
                });

        mvc.perform(put("/api/v1/ui/table-preferences/venta/stock.current")
                        .with(user("cashier"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "app":"venta",
                                  "tableKey":"stock.current",
                                  "columns":[
                                    {"key":"name","width":220},
                                    {"key":"code","visible":false}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns.length()").value(2))
                .andExpect(jsonPath("$.columns[0].visible").value(true))
                .andExpect(jsonPath("$.columns[1].width").doesNotExist())
                .andExpect(jsonPath("$.columns[1].visible").value(false));
    }

    @Test
    void rejectsUnauthenticatedAccessAndMalformedLayouts() throws Exception {
        mvc.perform(get("/api/v1/ui/table-preferences/venta"))
                .andExpect(status().isUnauthorized());

        mvc.perform(put("/api/v1/ui/table-preferences/venta/stock.current")
                        .with(user("cashier"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "app":"venta",
                                  "tableKey":"stock.current",
                                  "columns":[{"key":"name","width":801}]
                                }
                                """))
                .andExpect(status().isBadRequest());

        mvc.perform(put("/api/v1/ui/table-preferences/venta/stock.current")
                        .with(user("cashier"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "app":"venta",
                                  "tableKey":"stock.current",
                                  "columns":[{"key":"name","width":120.5}]
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
