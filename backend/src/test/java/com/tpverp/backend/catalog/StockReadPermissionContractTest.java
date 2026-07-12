package com.tpverp.backend.catalog;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.STOCK_READ;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tpverp.backend.promotion.PromotionController;
import com.tpverp.backend.promotion.PromotionService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest({ProductController.class, FamilyController.class, TaxController.class, PromotionController.class})
@Import(StockReadPermissionContractTest.MethodSecurityConfiguration.class)
class StockReadPermissionContractTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CatalogService catalog;

    @MockitoBean
    private ProductImageService images;

    @MockitoBean
    private PromotionService promotions;

    @Test
    void stockReadersCanLoadTheCatalogAndPromotionsRequiredByStockScreen() throws Exception {
        UUID familyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(catalog.products()).thenReturn(List.of());
        when(catalog.families()).thenReturn(List.of());
        when(catalog.subfamilies(familyId)).thenReturn(List.of());
        when(catalog.taxes()).thenReturn(List.of());
        when(catalog.selectableTaxes()).thenReturn(List.of());
        when(images.read(productId, true)).thenReturn(new ProductImageService.ProductImage(new byte[] {1}));
        when(promotions.list()).thenReturn(List.of());

        mvc.perform(get("/api/v1/products").with(stockReader()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/families").with(stockReader()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/families/{familyId}/subfamilies", familyId).with(stockReader()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/taxes").with(stockReader()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/taxes/selectable").with(stockReader()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/products/{productId}/image", productId)
                        .queryParam("thumbnail", "true")
                        .with(stockReader()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/promotions").with(stockReader()))
                .andExpect(status().isOk());
    }

    @Test
    void stockReadersCannotMutateCatalogOrPromotions() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(delete("/api/v1/products/{id}", id).with(stockReader()).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/families/{id}", id).with(stockReader()).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/taxes/{id}", id).with(stockReader()).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/promotions/{id}", id).with(stockReader()).with(csrf()))
                .andExpect(status().isForbidden());
    }

    private static RequestPostProcessor stockReader() {
        return user("stock-reader").authorities(() -> STOCK_READ);
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
