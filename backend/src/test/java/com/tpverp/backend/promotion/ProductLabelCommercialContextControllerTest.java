package com.tpverp.backend.promotion;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
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

@WebMvcTest(ProductLabelCommercialContextController.class)
@Import(ProductLabelCommercialContextControllerTest.MethodSecurityConfiguration.class)
class ProductLabelCommercialContextControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private ProductLabelCommercialContextService service;

    @Test
    void returnsCommercialContextToSalesUsers() throws Exception {
        var productId = UUID.randomUUID();
        when(service.resolve(List.of(productId))).thenReturn(List.of(
                new ProductLabelCommercialContextService.ProductCommercialContextView(
                        productId,
                        new ProductLabelCommercialContextService.OfferLabelView(
                                new BigDecimal("10.00"), new BigDecimal("8.00"),
                                new BigDecimal("20.00"), LocalDate.of(2026, 8, 31)),
                        List.of())));

        mvc.perform(post("/api/v1/products/sale/label-commercial-context")
                        .with(user("sales").authorities(() -> "VENTA"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[\"" + productId + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(productId.toString()))
                .andExpect(jsonPath("$[0].offer.offerPrice").value(8.00));
    }

    @Test
    void rejectsUsersWithoutProductSalePermissions() throws Exception {
        mvc.perform(post("/api/v1/products/sale/label-commercial-context")
                        .with(user("cashier").authorities(() -> "TICKETS_CREATE"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[\"" + UUID.randomUUID() + "\"]}"))
                .andExpect(status().isForbidden());
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
