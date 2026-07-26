package com.tpverp.backend.catalog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductEditAuthorizationController.class)
@Import(ProductEditAuthorizationControllerContractTest.MethodSecurityConfiguration.class)
class ProductEditAuthorizationControllerContractTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private ProductEditAuthorizationService service;

    @Test
    void authenticatedSellerCanSubmitDelegatedCredentialsForOneProduct() throws Exception {
        var productId = UUID.randomUUID();
        var operationId = UUID.randomUUID();
        var product = org.mockito.Mockito.mock(ProductView.class);
        when(product.id()).thenReturn(productId);
        when(service.authorize(
                eq(productId), eq("encargado"), eq("1234"), any()))
                .thenReturn(new ProductEditAuthorizationService.AuthorizationView(
                        operationId, "ENCARGADO", true,
                        Instant.parse("2026-07-24T12:15:00Z"), product));

        mvc.perform(post("/api/v1/pos/product-edit-authorizations")
                        .with(user("seller"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productId": "%s",
                                  "authorizerUsername": "encargado",
                                  "authorizerPassword": "1234"
                                }
                                """.formatted(productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationId").value(operationId.toString()))
                .andExpect(jsonPath("$.product.id").value(productId.toString()));
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
