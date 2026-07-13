package com.tpverp.backend.catalog;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_PRODUCTO;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@WebMvcTest(ProductPriceRuleController.class)
@Import(ProductPriceRuleControllerContractTest.MethodSecurityConfiguration.class)
class ProductPriceRuleControllerContractTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private ProductPriceRuleService service;

    @Test
    void productManagersCanCreateTypedRulesAndExecuteThem() throws Exception {
        UUID id = UUID.randomUUID();
        String form = """
                {"scope":"PRICES","conditions":[
                  {"type":"NUMBER","field":"GROSS_COST","comparator":"GTE","value":10}
                ],"actions":[
                  {"type":"FIXED_PRICE","field":"SALE_PRICE","value":20}
                ]}
                """;
        var manager = user("manager").authorities(() -> GESTION_PRODUCTO);

        mvc.perform(post("/api/v1/product-price-rules")
                        .with(manager).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Tarifa\",\"forms\":[" + form + "]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/product-price-rules/{id}/preview", id)
                        .with(manager).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleVersion\":0}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/product-price-rules/{id}/apply", id)
                        .with(manager).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleVersion\":0}"))
                .andExpect(status().isOk());
    }

    @Test
    void usersWithoutProductManagementAreForbidden() throws Exception {
        mvc.perform(get("/api/v1/product-price-rules").with(user("unauthorized")))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateRequiresVersion() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(put("/api/v1/product-price-rules/{id}", id)
                        .with(user("manager").authorities(() -> GESTION_PRODUCTO))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Tarifa","forms":[{"scope":"PRICES","conditions":[],
                                "actions":[{"type":"FIXED_PRICE","field":"SALE_PRICE","value":20}]}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void previewAndApplyRequireRuleVersion() throws Exception {
        UUID id = UUID.randomUUID();
        var manager = user("manager").authorities(() -> GESTION_PRODUCTO);

        mvc.perform(post("/api/v1/product-price-rules/{id}/preview", id)
                        .with(manager).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/product-price-rules/{id}/apply", id)
                        .with(manager).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void conflictResponseIncludesProductAndField() throws Exception {
        UUID id = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(service.preview(any(), any())).thenThrow(new ProductPriceRuleConflictException(
                productId,
                "Producto",
                ProductPriceRulePreview.Field.SALE_PRICE,
                List.of(0, 1)));

        mvc.perform(post("/api/v1/product-price-rules/{id}/preview", id)
                        .with(user("manager").authorities(() -> GESTION_PRODUCTO))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleVersion\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_PRICE_RULE_CONFLICT"))
                .andExpect(jsonPath("$.productId").value(productId.toString()))
                .andExpect(jsonPath("$.field").value("SALE_PRICE"));
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
