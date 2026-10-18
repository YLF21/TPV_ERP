package com.tpverp.backend.promotion;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.VENTA;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PromotionController.class)
@Import(PromotionPreviewApiTest.MethodSecurityConfiguration.class)
class PromotionPreviewApiTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PromotionService promotions;

    @Test
    void previewsSalePromotionsForVentaUsers() throws Exception {
        var promotionId = UUID.randomUUID();
        when(promotions.preview(any(PromotionPreviewRequest.class)))
                .thenReturn(new PromotionPreview(
                        List.of(new PromotionBenefit(
                                promotionId,
                                "3x2 Agua",
                                Set.of(1),
                                new BigDecimal("5.00"),
                                true,
                                "IVA",
                                new BigDecimal("7.00"))),
                        new BigDecimal("5.00")));

        mvc.perform(post("/api/v1/promotions/preview")
                        .with(user("seller").authorities(() -> VENTA))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [
                                    {
                                      "position": 1,
                                      "productId": "%s",
                                      "quantity": 3,
                                      "unitPrice": 5.00,
                                      "taxIncluded": true,
                                      "taxRegime": "IVA",
                                      "taxPercent": 7.00,
                                      "discountable": true
                                    }
                                  ]
                }
                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discountTotal").value(5.00))
                .andExpect(jsonPath("$.appliedPromotions[0].promotionId").value(promotionId.toString()))
                .andExpect(jsonPath("$.appliedPromotions[0].name").value("3x2 Agua"))
                .andExpect(jsonPath("$.appliedPromotions[0].amount").value(5.00))
                .andExpect(jsonPath("$.appliedPromotions[0].taxPercent").value(7.00));
    }

    @Test
    void updateAllowsProductManagementAndReturnsVersion() throws Exception {
        var promotion = Promotion.draft(UUID.randomUUID(), "Updated", PromotionType.SECOND_UNIT_PERCENT,
                LocalDate.of(2026, 7, 1));
        when(promotions.update(any(), any())).thenReturn(PromotionService.PromotionView.from(promotion, List.of(), 0));

        mvc.perform(put("/api/v1/promotions/{id}", promotion.id())
                        .with(user("manager").authorities(() -> "GESTION_PRODUCTO"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(updateJson("0")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(promotion.id().toString()))
                .andExpect(jsonPath("$.version").value(0));
        mvc.perform(put("/api/v1/promotions/{id}", promotion.id())
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(updateJson("0")))
                .andExpect(status().isOk());
    }

    @Test
    void updateRejectsSaleAndReadOnlyStockPermissions() throws Exception {
        for (var permission : List.of("VENTA", "STOCK_READ", "GESTION_VENTAS")) {
            mvc.perform(put("/api/v1/promotions/{id}", UUID.randomUUID())
                            .with(user("reader").authorities(() -> permission))
                            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(updateJson("0")))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(promotions);
    }

    @Test
    void updateRequiresVersionAndNestedValidPromotion() throws Exception {
        for (var json : List.of(updateJson("null"), updateJson("-1"),
                updateJson("0").replace("Updated", ""), "{\"expectedVersion\":0}")) {
            mvc.perform(put("/api/v1/promotions/{id}", UUID.randomUUID())
                            .with(user("manager").authorities(() -> "GESTION_PRODUCTO"))
                            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(promotions);
    }

    @Test
    void staleUpdateReturnsConflictCode() throws Exception {
        when(promotions.update(any(), any())).thenThrow(new OptimisticLockingFailureException("stale"));
        mvc.perform(put("/api/v1/promotions/{id}", UUID.randomUUID())
                        .with(user("manager").authorities(() -> "GESTION_PRODUCTO"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(updateJson("0")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROMOTION_VERSION_CONFLICT"));
    }

    private String updateJson(String version) {
        return """
                {"expectedVersion": %s, "promotion": {
                  "name": "Updated", "type": "SECOND_UNIT_PERCENT", "startDate": "2026-07-01",
                  "scope": "SALE", "customerSegment": "ALL", "discountPercent": 25, "targets": []
                }}
                """.formatted(version);
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
