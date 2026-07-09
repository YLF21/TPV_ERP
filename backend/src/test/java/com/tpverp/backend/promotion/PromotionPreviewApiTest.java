package com.tpverp.backend.promotion;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.VENTA;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
