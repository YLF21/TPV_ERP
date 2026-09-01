package com.tpverp.backend.catalog;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_WRITE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

@WebMvcTest(ProductController.class)
@Import(ProductClassificationMoveControllerWebMvcTest.MethodSecurityConfiguration.class)
class ProductClassificationMoveControllerWebMvcTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private CatalogService service;
    @MockitoBean private ProductImageService images;
    @MockitoBean private SaleProductCatalogService saleCatalog;

    @Test
    void returnsTypedConflictWithAllStaleRowsAndReloadAction() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(service.moveProducts(any(CatalogService.BulkMoveRequest.class)))
                .thenThrow(new ProductClassificationVersionConflictException(List.of(
                        new ProductClassificationVersionConflictException.Conflict(first, 2, 3),
                        new ProductClassificationVersionConflictException.Conflict(second, 4, 5))));

        mvc.perform(post("/api/v1/products/classification/move")
                        .with(user("manager").authorities(() -> PRODUCTS_WRITE))
                        .with(csrf())
                        .header("Accept-Language", "en-US")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "items": [
                                    {"productId": "%s", "expectedVersion": 2},
                                    {"productId": "%s", "expectedVersion": 4}
                                  ],
                                  "familyId": null,
                                  "subfamilyId": null
                                }
                                """.formatted(first, second)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:tpv-erp:error:PRODUCT_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.code").value("PRODUCT_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.action").value("RELOAD_PRODUCTS"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.conflicts.length()").value(2))
                .andExpect(jsonPath("$.locale").value("en"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
